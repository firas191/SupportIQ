# ADR-0008 — Sujets émergents : un instantané par exécution, croissance mesurée dans la fenêtre

- **Statut** : accepté
- **Date** : S7-J1
- **Contexte** : rapport §9 Semaine 7 — « Clustering des tickets récents (UMAP + HDBSCAN sur
  embeddings), étiquetage des clusters par LLM, job périodique » → livrable « Sujets émergents
  listés avec taille/croissance ».

## Problème

Le livrable demande une **croissance**. Un taux de croissance suppose deux mesures du *même objet*
à deux instants. Or l'objet, ici, est un groupe produit par un algorithme **non supervisé** : rien
dans HDBSCAN ne garantit qu'un groupe soit reconductible d'une exécution à l'autre.

Concrètement, entre l'instantané de mardi et celui de mercredi, un groupe peut :

- s'être **scindé** en deux (un motif se spécialise) ;
- avoir **absorbé** un voisin (deux motifs convergent) ;
- **disparaître** sous le seuil de densité sans que ses tickets aient cessé d'arriver ;
- **réapparaître** avec un libellé formulé autrement par le modèle.

La solution qui vient d'abord à l'esprit — donner une identité stable à chaque sujet et suivre son
volume jour après jour — demande donc de **rapprocher** les groupes de deux exécutions. Par quoi ?
Par ressemblance de libellé, ou par recouvrement des tickets. Les deux sont des heuristiques, et
l'historique qu'elles produiraient serait une **construction**, pas une mesure. Le pire est qu'il
serait invisible : une courbe est convaincante même quand ce qu'elle relie n'a pas d'unité.

## Décision

**Chaque exécution écrit un instantané complet et indépendant, et la croissance est calculée à
l'intérieur de la fenêtre analysée.**

- Toutes les lignes d'une exécution partagent un même `computed_at` — c'est ce qui fait l'unité de
  l'instantané, et la lecture ne retient que le plus récent (`WHERE computed_at = (SELECT MAX…)`).
- La fenêtre (14 jours par défaut) est coupée en deux moitiés. `recent_count` compte les tickets du
  groupe tombant dans la seconde moitié, `previous_count` dans la première. La croissance est le
  rapport des deux.
- `growth` vaut **`NULL`** quand la première moitié est vide : le sujet est apparu pendant la
  fenêtre. `+∞ %` n'est pas un chiffre, et `+100 %` ferait lire un doublement là où l'on est passé
  de rien à quelque chose. Même choix qu'au digest (S6-J4) ; l'interface en tire « nouveau », qui
  dit davantage qu'un pourcentage.
- Aucun sujet n'est apparié entre deux exécutions. Les instantanés s'empilent sans être reliés.

L'affirmation « ce sujet monte » devient ainsi vérifiable **à l'intérieur d'un seul instantané**,
avec ses deux termes affichés côte à côte, sans dépendre d'aucune exécution passée.

## Conséquences

**Assumées.**

- La croissance est **relative à la fenêtre** : sur 14 jours, elle compare 7 jours à 7 jours. Ce
  n'est pas une tendance de fond, c'est un signal d'accélération récente — ce que cherche
  précisément un responsable qui veut savoir ce qui a changé cette semaine.
- Deux instantanés de **fenêtres différentes ne se comparent pas**. Le paramètre existe (démo,
  rattrapage après un import massif) mais l'écran et l'API le disent.
- On ne peut pas répondre à « ce sujet occupe-t-il l'équipe depuis trois semaines ? ». C'est une
  vraie limite. Elle est acceptée parce que la réponse fabriquée aurait été pire que l'absence de
  réponse.

**Porte de sortie.** Les instantanés ne sont **jamais supprimés**. Le jour où un rapprochement
inter-exécutions sera voulu, la matière est là — et il faudra alors le construire sur le
**recouvrement des tickets** (mesurable, vérifiable), jamais sur la ressemblance des libellés, qui
dépend d'un modèle et de sa température.

## Décisions techniques subordonnées

- **UMAP avant HDBSCAN.** En 768 dimensions les distances se concentrent et la notion de région
  dense perd son sens ; sans réduction, HDBSCAN renvoie un groupe géant et beaucoup de bruit. UMAP
  plutôt qu'une ACP parce qu'il préserve la structure **locale** — or un sujet émergent est par
  définition un petit groupe serré, exactement ce qu'une ACP écrase.
- **HDBSCAN plutôt que k-moyennes.** On ne connaît pas le nombre de sujets, et surtout k-moyennes
  **affecte tous les points**. La majorité des tickets ne relèvent d'aucun sujet : HDBSCAN les
  classe comme **bruit**, et ce refus de conclure est la fonctionnalité principale — c'est lui qui
  empêche d'inventer des tendances dans du hasard.
- **`sklearn.cluster.HDBSCAN` et non le paquet `hdbscan`.** Même algorithme, mais scikit-learn est
  déjà tiré par sentence-transformers et n'exige aucune compilation.
- **Graine fixée** (`random_state=42`). UMAP est stochastique ; sans graine, deux exécutions sur les
  mêmes données donneraient des groupes différents et la liste changerait d'un rechargement à
  l'autre sans que rien n'ait bougé. Le coût est réel — fixer la graine désactive le parallélisme
  d'UMAP — mais un travail nocturne peut se permettre d'être lent, pas d'être capricieux.
- **Le modèle nomme, le code compte.** Application de la règle du S5-J3 : nommer un ensemble de
  textes à partir de ce qu'ils ont en commun est un jugement ; compter des tickets et diviser n'en
  est pas un. Les tickets montrés au modèle sont les plus **centraux** du groupe, et un repli
  déterministe (le sujet du ticket central) garantit qu'aucun groupe ne reste anonyme si le modèle
  est indisponible.
- **Pas de rattrapage horaire**, contrairement au digest. Un digest manqué est un document qui
  n'existera jamais pour cette semaine ; un instantané manqué est remplacé le lendemain par un
  calcul portant sur la même fenêtre glissante. C'est pour cela que l'écran affiche visiblement sa
  date de calcul.

## Alternatives écartées

- **BERTopic** (cité dans les dépendances prévisionnelles du rapport). Il enchaîne exactement les
  mêmes étapes — embeddings, UMAP, HDBSCAN, étiquetage — mais impose son propre pipeline
  d'embeddings, alors que le projet en a déjà un (`multilingual-e5-base`, S3-J4) dont les vecteurs
  sont **déjà stockés** dans pgvector. L'utiliser aurait signifié ré-embedder le corpus avec un
  second modèle, pour un résultat qu'on obtient en trois appels de bibliothèque. La brique qu'il
  apporte réellement — l'étiquetage c-TF-IDF — est justement celle qu'on ne veut pas : elle produit
  des listes de mots-clés, pas des intitulés lisibles.
- **Étiquetage par mots-clés (c-TF-IDF, KeyBERT).** Déterministe et gratuit, mais « paiement,
  carte, échec, débit » n'est pas un nom de sujet. La page existe pour être lue en dix secondes par
  un responsable ; une liste de mots demande de reconstruire soi-même la phrase.
- **Une identité de sujet persistante avec appariement par libellé.** Écartée ci-dessus : produit un
  historique inventé, et le produit de façon invisible.
