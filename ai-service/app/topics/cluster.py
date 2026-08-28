"""Regroupement des tickets récents par proximité de sens (S7-J1, rapport §9).

```
embeddings (768 dim) ──UMAP──> ~8 dim ──HDBSCAN──> groupes + bruit
```

**Pourquoi réduire avant de regrouper.** HDBSCAN mesure des densités. En 768 dimensions, les
distances se concentrent — tous les points finissent à peu près aussi loin les uns des autres, et
la notion de « région dense » perd son sens. C'est la malédiction de la dimension, et elle n'est
pas théorique : sans réduction, HDBSCAN renvoie ici un seul groupe géant et beaucoup de bruit.

**Pourquoi UMAP plutôt qu'une ACP.** Une ACP cherche les directions de plus grande variance, donc
elle conserve la structure *globale* et écrase les petits voisinages — exactement ce qui nous
intéresse ici, où un sujet émergent est par définition un petit groupe serré. UMAP préserve la
structure *locale*, ce qui est le bon objectif quand la suite du traitement est un algorithme de
densité.

**Pourquoi HDBSCAN plutôt que k-moyennes.** Deux raisons décisives : on ne connaît pas le nombre de
sujets à l'avance, et surtout k-moyennes **affecte tous les points** à un groupe. Or la plupart des
tickets ne relèvent d'aucun sujet émergent : ce sont des demandes isolées. HDBSCAN les classe comme
**bruit**, et ce refus de conclure est une fonctionnalité, pas une limite — il évite d'inventer des
tendances dans du hasard.

**Reproductibilité.** `random_state` est fixé. UMAP est stochastique ; sans graine, deux exécutions
sur les mêmes données donneraient des groupes différents, et un responsable qui recharge la page
verrait la liste changer sans raison. Le coût est réel — fixer la graine désactive le parallélisme
d'UMAP — mais un travail nocturne peut se permettre d'être lent, pas d'être capricieux.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass

logger = logging.getLogger(__name__)

#: Graine fixe : la même entrée doit donner le même découpage.
RANDOM_STATE = 42

#: Dimensions après réduction. Assez pour séparer des sujets, assez peu pour que la densité ait un
#: sens. Au-delà d'une dizaine, le bénéfice de la réduction s'estompe.
TARGET_DIMENSIONS = 8

#: Taille minimale d'un groupe. En dessous, ce n'est pas un sujet émergent — c'est une coïncidence.
#: Trois tickets qui se ressemblent arrivent tous les jours sans rien signifier.
MIN_CLUSTER_SIZE = 8


@dataclass
class Cluster:
    """Un groupe de tickets proches. `indices` référence les positions dans l'entrée."""

    indices: list[int]

    @property
    def size(self) -> int:
        return len(self.indices)


def find_clusters(vectors: list[list[float]]) -> list[Cluster]:
    """Regroupe des vecteurs. Renvoie une liste vide si le corpus est trop petit.

    Le bruit (`label == -1`) est **écarté volontairement** : ce sont les tickets qui n'appartiennent
    à aucune région dense. Les forcer dans un groupe reviendrait à fabriquer un sujet à partir de
    demandes sans rapport.
    """
    if len(vectors) < MIN_CLUSTER_SIZE * 2:
        logger.info("Corpus trop petit pour un regroupement (%d tickets)", len(vectors))
        return []

    import numpy as np
    from sklearn.cluster import HDBSCAN

    matrix = np.asarray(vectors, dtype="float32")
    reduced = _reduce(matrix)

    # `sklearn.cluster.HDBSCAN` (>= 1.3) plutot que le paquet `hdbscan` : meme algorithme, mais
    # scikit-learn est deja present (dependance de sentence-transformers) et n'exige aucune
    # compilation. Une dependance en moins sur une image qui en porte deja beaucoup.
    labels = HDBSCAN(
        min_cluster_size=MIN_CLUSTER_SIZE,
        # Un point isolé ne doit pas suffire à amorcer un groupe.
        min_samples=3,
        # `eom` (excess of mass) : le **défaut** de HDBSCAN.
        #
        # J'avais d'abord posé `leaf`, en justifiant qu'il donnerait « des sujets précis plutôt
        # que de grandes familles ». C'était une déviation du défaut appuyée sur une intuition,
        # pas sur une mesure — et une déviation non mesurée est une hypothèse.
        #
        # `leaf` coupe l'arbre condensé à ses feuilles, donc au grain le plus fin que la densité
        # permette ; `eom` retient les groupes de masse maximale, à des échelles variables. Sans
        # connaître d'avance la granularité utile, `eom` est le choix sûr : sous-segmenter donne
        # des sujets un peu larges mais justes, sur-segmenter donne un écran où le même problème
        # apparaît sept fois — illisible, donc inutilisable.
        #
        # **Ce que la première exécution n'a PAS permis de trancher.** Elle a produit vingt groupes
        # quasi synonymes, ce que j'ai d'abord attribué à `leaf`. C'était faux : le corpus de test
        # ne contenait que **10 corps et 18 sujets distincts** pour 2 983 tickets, chaque texte
        # répété une trentaine de fois. Des textes identiques donnent des vecteurs identiques,
        # donc des amas de variance nulle parfaitement séparés — HDBSCAN y trouve ~90 groupes
        # *réels*, quel que soit le mode de sélection. Le corpus a été corrigé ; la qualité du
        # regroupement reste à évaluer sur des données variées.
        cluster_selection_method="eom",
    ).fit_predict(reduced)

    clusters: dict[int, list[int]] = {}
    for index, label in enumerate(labels):
        if label >= 0:  # -1 = bruit, ecarte
            clusters.setdefault(int(label), []).append(index)

    found = [Cluster(indices=indices) for indices in clusters.values()]
    found.sort(key=lambda c: c.size, reverse=True)

    noise = sum(1 for label in labels if label < 0)
    logger.info(
        "Regroupement: %d sujets sur %d tickets (%d hors sujet)",
        len(found), len(vectors), noise,
    )
    return found


def _reduce(matrix):
    """Réduit la dimension avec UMAP, ou renonce proprement s'il est absent.

    Import paresseux et repli explicite : UMAP tire `numba` et `llvmlite`, dépendances lourdes qui
    peuvent manquer sur un environnement allégé. Dans ce cas on regroupe sur les vecteurs bruts —
    la qualité se dégrade, mais la fonctionnalité existe encore, et le journal le dit.
    """
    try:
        import umap
    except ImportError:
        logger.warning("UMAP absent : regroupement sur les vecteurs bruts, qualite degradee")
        return matrix

    reducer = umap.UMAP(
        n_components=TARGET_DIMENSIONS,
        # Voisinage restreint : on privilegie la structure locale, celle qui fait les petits
        # groupes serres qu'on cherche.
        n_neighbors=15,
        min_dist=0.0,
        # Les embeddings e5 sont normalises : la distance cosinus est la mesure naturelle.
        metric="cosine",
        random_state=RANDOM_STATE,
    )
    return reducer.fit_transform(matrix)


def centroid_order(vectors: list[list[float]], indices: list[int], limit: int) -> list[int]:
    """Indices des membres les plus **centraux** d'un groupe, du plus proche au plus lointain.

    Sert à choisir les tickets montrés au modèle pour qu'il rédige un libellé. Prendre les premiers
    venus donnerait parfois des cas limites, en bordure du groupe, et le libellé décrirait mal
    l'ensemble. Le centre d'un groupe est ce qui le représente.
    """
    import numpy as np

    members = np.asarray([vectors[i] for i in indices], dtype="float32")
    centroid = members.mean(axis=0)
    distances = np.linalg.norm(members - centroid, axis=1)
    ranked = np.argsort(distances)[:limit]
    return [indices[int(position)] for position in ranked]
