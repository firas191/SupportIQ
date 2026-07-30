# Système de design SupportIQ

> Document de référence de la refonte d'interface (post-S4).
> Il précède le code : chaque décision visuelle y est justifiée par un usage,
> pas par un goût. Il sert aussi de support à la partie « interface » du
> rapport de stage.

---

## 1. Pour qui, et pour quoi

Trois profils, trois fréquences d'usage radicalement différentes. C'est la
donnée la plus structurante de toute la refonte.

| Profil | Ce qu'il vient faire | Fréquence | Conséquence sur l'interface |
|---|---|---|---|
| **Agent** | Traiter la file : repérer l'urgent, lire, corriger un classement, fusionner un doublon | Toute la journée, des centaines d'interactions | L'écran le plus dense, le plus rapide, entièrement pilotable au clavier |
| **Responsable** | Répondre à « est-ce que ça va ? » | 1 à 2 fois par jour, 30 secondes | Une page qui se lit en un coup d'œil, pas un inventaire |
| **Administrateur** | Verser un historique, créer un accès | Quelques fois par mois | Accessible mais en retrait, jamais au même niveau que le travail quotidien |

**Principe directeur : le poids visuel doit être proportionnel à la fréquence
d'usage.** Tout ce qui suit en découle.

---

## 2. Ce qui n'allait pas, et ce qui a changé

| Problème constaté | Correction | Pourquoi |
|---|---|---|
| La liste des tickets n'affichait ni priorité, ni catégorie, ni humeur du client — on pouvait *filtrer* dessus sans jamais les *voir* | Colonnes ajoutées, priorité en tête | C'est exactement ce que le produit apporte. Le cacher, c'est nier sa valeur |
| Sept listes déroulantes alignées sur deux rangs | Onglets de statut + panneau de filtres en pastilles | Le statut est le filtre le plus utilisé : 1 clic au lieu de 2, options visibles sans déplier |
| Après trois filtres, on ne savait plus ce qui était actif | Pastilles de filtres actifs, retirables à l'unité | L'état de recherche devient lisible et réversible |
| « Imports » et « Équipe » au même niveau que « Tickets » | Regroupés sous « Administration », en bas | Une action mensuelle ne doit pas peser autant qu'une action quotidienne |
| Le tableau de bord ouvrait sur « Tickets au total » | Ouvre sur « À traiter », puis « Urgences », puis « Clients mécontents » | Un compteur ne déclenche aucune décision ; une charge de travail, si |
| `NEG`, `WEBHOOK`, `escalade LLM`, `confiance 0.87` | `Mécontent`, `Temps réel`, `Analyse approfondie`, `Fiabilité 87 %` | Le vocabulaire d'ingénieur n'a rien à faire devant un agent du support |
| Aucun état vide, aucun squelette, aucune page 404 | Traités partout | Un écran vide non traité est perçu comme une panne |
| Adresse inconnue → redirection silencieuse | Page 404 explicite | Se retrouver ailleurs sans explication fait croire à un clic raté |
| Trois listes déroulantes pour corriger un classement | Groupes de pastilles | La correction passe de 2 clics à 1, et les valeurs possibles sont visibles |
| La fusion de doublons s'exécutait au clic | Dialogue de confirmation énonçant la conséquence | Seule action difficile à annuler de l'application |

---

## 3. Philosophie

1. **Le contenu est l'interface.** Bordures, ombres et fonds reculent d'un cran
   par rapport aux valeurs par défaut de Material.
2. **La couleur porte une information, ou elle n'existe pas.** Rouge = urgent
   ou négatif. Ambre = attention. Vert = résolu ou positif. Indigo = action et
   sélection. Tout le reste est neutre.
3. **La densité est un choix par écran.** La table est dense (un agent balaye
   50 lignes) ; le tableau de bord est aéré (on y lit cinq chiffres).
4. **Redondance des signaux.** Un état actif se lit à la fois par la couleur,
   le poids du trait et un marqueur de position — donc aussi en cas de
   daltonisme.
5. **Toute animation a une fonction.** Attirer, expliquer une transition, ou
   confirmer une action. Sinon, elle est supprimée.

---

## 4. Identité

- **Nom** : SupportIQ. Monogramme en bouclier + coche : la protection et la
  vérification, les deux promesses du produit.
- **Accent** : indigo/violet profond (`#5d51d8`). Registre Linear/Stripe.
  Choisi parce qu'il ne rentre en collision avec aucune des couleurs
  sémantiques de statut — un accent vert ou rouge aurait été ambigu.
- **Typographie** : **Inter**. Hauteur d'x élevée, chiffres tabulaires,
  dessinée pour les petites tailles à l'écran.
- **Icônes** : **Material Symbols Rounded**, police variable — le remplissage
  et la graisse se pilotent en CSS, ce qui permet l'icône pleine sur l'entrée
  de menu active pour zéro octet supplémentaire.

---

## 5. Couleur

Deux niveaux : des **palettes brutes** (gris, indigo, sémantiques) et des
**alias sémantiques** que seuls les composants consomment. Un composant demande
« la couleur du texte secondaire », jamais « gris 600 ».

```
Fonds       --bg-canvas / --bg-surface / --bg-surface-2 / --bg-sunken
Bordures    --border-subtle / --border-default / --border-strong
Texte       --text-primary / --text-secondary / --text-tertiary / --text-disabled
Accent      --accent (+ hover, active, soft-bg, soft-fg)
Sémantique  --danger / --warning / --success / --info  (+ -fg, -bg, -border)
Métier      --cat-technique / --cat-facturation / --cat-compte / …
```

**Thème sombre** : seuls les alias sont réécrits, aucun composant ne change.
Deux ajustements que le simple « inverser les gris » rate :

- les fonds **montent** en clarté avec l'élévation (le canvas est le plus
  sombre), à l'inverse du thème clair où la carte blanche flotte sur un gris ;
- les couleurs saturées sont **éclaircies et désaturées** : sur fond sombre, un
  rouge `#e5484d` vibre et devient illisible.

Le thème est appliqué par un script synchrone dans `index.html`, **avant le
premier rendu** — sans quoi l'application démarre en clair puis bascule, soit
un flash blanc très visible.

---

## 6. Typographie

Échelle de ratio ~1.2, en pixels entiers pour rester nette sur écran non-retina.

| Token | Taille | Usage |
|---|---|---|
| `--text-xs` | 11 px | Micro-labels en capitales, badges |
| `--text-sm` | 12 px | Métadonnées, extraits, indices |
| `--text-base` | 13 px | Texte de table, boutons |
| `--text-md` | 14 px | Corps de texte |
| `--text-lg` | 16 px | Titres de carte |
| `--text-xl` | 19 px | Titres de page |
| `--text-3xl` | 28 px | Valeurs d'indicateur |
| `--text-4xl` | 34 px | Accroche de la page de connexion |

**Tous les chiffres sont tabulaires** (`font-variant-numeric: tabular-nums`).
Sans cela, une colonne de nombres tremble à chaque mise à jour — visible en
permanence sur un compteur temps réel.

**Les capitales sont réservées** aux intitulés de métrique et aux en-têtes de
colonne. Nulle part ailleurs : au-delà de trois mots, elles ralentissent la
lecture.

---

## 7. Espacement, rayons, élévation

- **Espacement** : base 4 px — 4, 8, 12, 16, 20, 24, 32, 40, 56, 72.
- **Rayons** : 4 / 6 / 8 / 12 / 16 / 20 / plein. Un rayon par famille d'objet :
  badge (6), bouton et champ (8), carte (12), dialogue (16).
- **Élévation** : **trois niveaux seulement**. Chacun combine une ombre de
  contact serrée et une ombre de diffusion large — c'est ce qui évite l'effet
  « boîte grise » des ombres à un seul rayon. En thème sombre, une ombre noire
  ne se voit pas : la profondeur vient d'ombres très larges.

---

## 8. Composants

**Primitives globales** (classes CSS, sans composant Angular) : `card`, `btn`,
`badge`, `input`, `search-field`, `segmented`, `data-table`, `banner`,
`empty-state`, `skeleton`, `meter`, `kbd`, `pill`.
*Pourquoi des classes* : un badge n'a pas d'état. En faire un composant
ajouterait un nœud DOM et une frontière de style pour rien.

**Composants Angular** (là où il y a état ou comportement) : `app-icon`,
`app-badge`, `app-stat-card`, `app-sparkline`, `app-count-up`,
`app-empty-state`, `app-skeleton`, `app-page-header`, `app-command-palette`,
`app-confirm-dialog`, `app-chart`.

### Angular Material : ni remplacé, ni gardé tel quel

- **Remplacé** quand le composant n'est que de la présentation : carte, table,
  bouton, barre d'outils, barre de progression, bascule. Les surcharger
  coûterait plus cher que les réécrire, pour un résultat toujours approximatif.
- **Conservé** quand il apporte un **comportement** difficile à refaire
  correctement : select (listbox ARIA + positionnement d'overlay), menu
  (navigation clavier), dialogue (piège de focus), snack-bar (file d'attente +
  région live), tooltip. Ceux-là sont reteintés dans `_material.scss`.

L'ondulation Material (« ripple ») est désactivée : métaphore tactile venue du
mobile, elle ajoute ~200 ms de latence perçue à chaque clic sur un poste de
travail. Le retour vient de l'état `:active`, instantané.

---

## 9. Mise en page

- Grille à deux colonnes (barre latérale + contenu), et non une barre en
  `position: fixed` : la zone de contenu connaît sa largeur réelle, donc les
  tables et les graphiques se dimensionnent sans calcul JavaScript.
- Barre latérale **repliable en rail de 64 px**, choix mémorisé. Sur un écran
  de portable, cela rend ~180 px à la table.
- Barre du haut **translucide** (`backdrop-filter`) : le contenu qui défile
  dessous reste deviné, ce qui l'ancre comme une couche.
- Contenu borné à 1480 px, centré.

---

## 10. Le tableau de bord

Ordre de lecture par valeur d'action : **À traiter** → **Urgences** →
**Clients mécontents** → **Résolus**. Les graphiques viennent après : ils
expliquent les chiffres, ils ne les remplacent pas.

Choix de représentation :

- **Motifs de contact en barres classées**, pas en camembert. Comparer cinq
  secteurs angulaires est difficile ; comparer cinq longueurs alignées sur une
  même base est immédiat.
- **Humeur en anneau** : trois parts d'un tout, la forme se lit comme une jauge.
- **Volume en aires empilées** : la hauteur totale donne le volume global,
  chaque bande donne la part d'une catégorie. Deux lectures en un dessin.
- **Affluence horaire à opacité proportionnelle** : les heures de pointe
  ressortent avant même de lire l'axe.
- **Aucune grille verticale** : elle n'aide jamais à lire une valeur.

Les couleurs sont résolues depuis les tokens à chaque recalcul et les
`computed` dépendent du signal de thème : les graphiques suivent la bascule
clair/sombre comme le reste de la page.

---

## 11. Adaptatif

| Largeur | Comportement |
|---|---|
| > 1080 px | Deux colonnes sur la fiche ticket, rail latéral collant |
| ≤ 1080 px | Fiche ticket en une colonne, rail rendu au flux |
| ≤ 900 px | Barre latérale en tiroir superposé ; colonnes Origine et Client masquées |
| ≤ 620 px | Colonne Catégorie masquée, palette pleine largeur |
| ≤ 560 px | Barre du haut réduite aux icônes |

Les colonnes de contexte sont **masquées** plutôt que laissées en défilement
horizontal : un défilement latéral dans une liste verticale est désorientant.

---

## 12. Accessibilité

- **Anneau de focus unique**, sur `:focus-visible` — visible au clavier,
  invisible à la souris.
- **Lien d'évitement** en premier élément tabulable.
- **Lignes de table focalisables** (`tabindex`), Entrée et Espace ouvrent la
  fiche ; cible de 48 px sur toute la largeur.
- `aria-sort` sur les en-têtes triables, `aria-pressed` sur les bascules,
  `aria-current="page"` sur l'entrée de navigation active.
- `role="status"` + `aria-live="polite"` sur le bandeau temps réel : annoncé
  sans interrompre.
- Palette de commandes : `role="dialog"` modal, `aria-activedescendant`, focus
  placé à l'ouverture et **rendu** à l'élément précédent à la fermeture.
- `prefers-reduced-motion` : les animations non essentielles sont supprimées,
  pas seulement raccourcies.
- Contrastes ≥ 4.5:1 sur le texte courant dans les deux thèmes.

---

## 13. Mouvement

Trois durées, deux courbes. Toute animation hors de ce cadre est décorative,
donc supprimée.

| Token | Durée | Usage |
|---|---|---|
| `--duration-fast` | 110 ms | Survol, pression — retour immédiat |
| `--duration-base` | 180 ms | Ouverture, bascule d'état |
| `--duration-slow` | 280 ms | Entrée d'un panneau, d'un dialogue |

Les entrées de contenu translatent de **6 px seulement** : au-delà, le
mouvement se remarque — or il doit se sentir, pas se voir.

---

## 14. Performance perçue

- **Premier chargement d'un écran → squelette**, avec la géométrie exacte du
  contenu à venir, donc aucun saut de mise en page.
- **Rechargement (filtre, page) → fine barre de progression**, et les données
  précédentes restent affichées. Vider une table pour la remplir 200 ms plus
  tard est désagréable et inutile.
- **Écran d'amorçage** dans `index.html` pendant le téléchargement du bundle.
- **Chargement différé par route** : un agent qui ne va jamais dans les imports
  n'en télécharge jamais le code.
- Sparklines en SVG écrit à la main : instancier un moteur de graphique complet
  par carte coûterait un canvas et une boucle d'animation pour douze segments.

---

## 15. La seule modification backend de la refonte

Les colonnes **Priorité**, **Catégorie** et **Humeur** de la liste demandaient
une donnée que l'API ne renvoyait pas : elle permettait déjà de *filtrer* sur
ces champs (la jointure `analyses` est en place depuis la recherche full-text)
mais pas de les *retourner*.

Trois colonnes ajoutées au `SELECT` existant, trois champs ajoutés au DTO.
**Aucune requête supplémentaire** — la jointure était déjà payée. Le contrat est
*étendu*, jamais cassé : les champs sont ajoutés en fin d'enregistrement, aucun
champ existant n'est modifié ni supprimé.

Ces champs valent `null` tant que le ticket n'est pas analysé (jointure
externe). La liste affiche alors **« en attente »** plutôt qu'une case vide :
un ticket qui vient d'arriver et n'est pas encore classé est une information
utile pour un agent, pas une donnée manquante.

Reste volontairement de côté : **le tri sur ces colonnes**. Trier la priorité
par ordre alphabétique donnerait `HIGH, LOW, MEDIUM` — un classement faux. Il
faudrait un ordre métier explicite dans la liste blanche de tri côté serveur, à
faire seulement si le besoin apparaît.
