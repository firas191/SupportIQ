# ADR-0010 — Risque de dépassement SLA : décision pré-enregistrée entre le modèle et la règle

- **Statut** : **accepté** — mesuré le 28/08/2026, décision appliquée : **le modèle n'est pas
  déployé**, la règle reste le chemin de production. Voir « Résultat » en fin de document.
- **Date** : S7-J3
- **Contexte** : rapport §9 Semaine 7 — « Modèle de risque SLA : features (catégorie, priorité,
  sentiment, heure, backlog courant), gradient boosting (LightGBM), calibration ; score affiché +
  tri "at risk" dans la file. » Livrable attendu : « AUC documentée ; colonne SLA risk dans la
  liste ».

## Problème

**Il n'existe aucune vérité terrain.** Le label recherché est « ce ticket a-t-il dépassé son
SLA ? ». Le calculer demande deux choses : une échéance, et un instant de résolution.

- `sla_due_at` existait depuis la V2 et **n'avait jamais été remplie** — c'était le seul endroit du
  schéma où une donnée était prévue puis oubliée ;
- `resolved_at` **n'existait pas du tout** : le statut `RESOLVED` disait qu'un ticket avait été
  résolu, jamais quand.

Et surtout : aucun ticket du projet n'a jamais été résolu, le corpus étant synthétique et importé
en bloc.

Entraîner un modèle sur des labels qu'on ne possède pas, puis afficher une AUC, serait du théâtre.
La question de la journée n'est donc pas « quel modèle », mais **« que peut-on honnêtement livrer,
et comment le dire »**.

## Décision

### 1. Combler d'abord ce qui manque au schéma (V17)

`resolved_at` est ajoutée aujourd'hui **même si aucun ticket n'est encore résolu**. C'est le jour
où l'on commence à accumuler l'historique qui rendra la mesure honnête possible : *une donnée de
vérité terrain qu'on n'enregistre pas est une mesure qu'on s'interdit pour toujours.*

`sla_due_at` est remplie par une politique lisible — HIGH 4 h, MEDIUM 24 h, LOW 72 h, priorité
inconnue traitée comme du courant. Traiter l'inconnu comme urgent aurait fait basculer 10 000
tickets non analysés en rouge le jour du déploiement, ce qui revient à n'avoir plus aucune urgence.

### 2. La règle est le chemin par défaut, pas un bouche-trou

Le service renvoie **la part du budget SLA déjà consommée** tant qu'aucun artefact entraîné n'est
déployé — ce qui est l'état par défaut. Ce n'est pas un mode dégradé : c'est exactement ce qu'un
responsable calcule de tête, c'est monotone, interprétable, et c'est juste sur le cas dominant (un
ticket dont l'échéance est passée dépasse effectivement son SLA).

Même architecture que le triage (S3-J3) : chargement paresseux, repli déterministe, provenance
stockée avec le score (`rules` ou `lightgbm`) pour la même raison qu'au S5-J5 (`judged_by`) et au
S7-J2 (`method`).

### 3. Le modèle est entraîné sur un historique simulé, et le dit

`ml/train_sla_risk.py` fabrique un historique dont **toutes les règles sont écrites en clair** dans
le docstring de `simulate()`, avec un facteur explicitement non observé (la disponibilité des
agents) qui borne l'AUC atteignable. Sans ce facteur, LightGBM retrouverait la règle exactement et
l'AUC vaudrait ~1,0 — un chiffre qui n'aurait trompé que celui qui l'affiche.

Le rapport généré porte l'avertissement en tête :

> L'AUC mesure la capacité de LightGBM à retrouver les règles de mon simulateur. Elle ne dit rien
> de sa performance sur des tickets réels.

### 4. Décision **pré-enregistrée** — écrite avant les chiffres

Après l'épisode du reranking (S5-J2), où l'agrégat m'avait fait conclure trop vite, et après
ADR-0006 où le pré-enregistrement a effectivement choisi une option que je n'aurais pas retenue
après coup, la règle est posée d'avance :

| Écart d'AUC (modèle − règle) sur le test simulé | Décision |
|---|---|
| **≥ 0,08** | L'artefact est déployé. Le modèle apporte quelque chose que la règle ne capte pas — nécessairement l'encombrement de la file et l'heure d'arrivée, les seules variables que la règle ignore. |
| **entre 0,03 et 0,08** | L'artefact **n'est pas** déployé. Le gain est réel mais trop faible pour justifier, sur des données simulées, une dépendance de production (lightgbm, un artefact à versionner, un modèle à surveiller). À rejouer sur les premiers mois d'historique réel. |
| **< 0,03** | L'artefact n'est pas déployé et la piste est close jusqu'à disposer de vraies données. Si le modèle ne bat pas la règle sur un historique que j'ai moi-même fabriqué avec des variables qu'il observe, il ne la battra pas en production. |

Dans les trois cas, **le code du modèle reste en place** : il est le chemin par lequel les données
réelles seront exploitées quand elles existeront, et il n'a de coût que s'il est chargé.

### 5. Calibration exportée en table, jamais en `pickle`

La régression isotone apprise est exportée en liste de points `(x, y)` et appliquée au service par
interpolation linéaire (bibliothèque standard). Un `CalibratedClassifierCV` sérialisé coupleraient
l'artefact aux versions exactes de scikit-learn, numpy et Python qui l'ont produit, et échouerait
au chargement des mois plus tard, en production, sans que rien ne l'ait annoncé.

**Pourquoi calibrer du tout.** LightGBM optimise une log-loss : il produit des scores *ordonnés*,
pas des probabilités. Un modèle peut classer parfaitement (AUC élevée) et annoncer 0,8 sur des
tickets qui dépassent une fois sur deux. Or ce chiffre est affiché à un responsable qui décide
d'agir. **Une AUC honnête et une interface qui ment sont parfaitement compatibles** — c'est
précisément ce que le Brier mesure et pas l'AUC.

### 6. Le seuil « à risque » est une décision d'exploitation, pas une propriété du modèle

0,70 par défaut. Il fixe la taille de la file prioritaire et devrait se régler sur la capacité de
l'équipe. Il est donc **global** et non réglable par utilisateur : sinon deux responsables parlant
de « la file à risque » ne parleraient pas de la même file.

### 7. Le score est stocké, daté, et son âge est visible

Stocké parce que le tri et la pagination se font en SQL : une valeur calculée dans l'application ne
peut pas participer à un `ORDER BY ... LIMIT`. Recalculé toutes les dix minutes — 2,5 % du budget le
plus court. Daté parce que sa variable dominante est le temps restant : un score affiché sans sa
date se lirait comme une valeur instantanée.

## Conséquences

- **La colonne « Échéance » de la file fonctionne dès aujourd'hui**, sans modèle, sans artefact,
  sans entraînement. C'est ce qui rend la décision 4 possible : rien n'est bloqué par son résultat.
- Trois paliers affichés (« Serré » / « À surveiller » / « Confortable »), jamais un pourcentage
  dans la colonne. « 62 % » suggère une précision que le modèle n'a pas, et personne n'agit
  différemment à 62 % et à 67 %. Le chiffre exact, la provenance et la date sont dans l'info-bulle.
- La politique SLA est écrite **à trois endroits** (migration V17, `SlaPolicy` en Java,
  `features.py` en Python). Duplication assumée : la migration doit rattraper l'existant sans
  dépendre de l'application, l'application doit dater les tickets à venir sans repasser par une
  migration, et le service IA doit pouvoir scorer sans appeler Spring. Une abstraction partagée
  entre SQL, Java et Python coûterait plus que ces trois valeurs.
- **À dire tel quel en soutenance** : « le modèle est construit, mesuré et instrumenté ; il n'est
  pas branché parce que je n'ai pas de données pour le juger. La colonne repose sur une règle, et
  la règle est honnête. »

## Résultat (28/08/2026)

Exécution de `ml/train_sla_risk.py` sur 20 000 tickets simulés, 15 000 en apprentissage et 5 000 en
test, taux de dépassement observé **16,5 %**.

| | AUC | Brier |
|---|---|---|
| Règle — part du budget consommée | **0,896** | 0,0783 |
| LightGBM, score brut | **0,970** | 0,0487 |
| LightGBM + calibration isotone | 0,970 | 0,0504 |

**Écart d'AUC : +0,074.** La bande *0,03 – 0,08* s'applique : **l'artefact n'est pas déployé**, la
règle reste le chemin de production, et la piste sera rejouée sur les premiers mois d'historique
réel.

### Pourquoi la décision n'est pas relitigée à 0,006 du seuil

L'écart tombe à six millièmes du seuil de déploiement. C'est précisément la situation pour laquelle
la règle a été écrite d'avance : sans elle, « 0,074, c'est pratiquement 0,08 » serait irrésistible,
et la décision se prendrait sur l'envie de déployer plutôt que sur un critère.

Trois éléments confortent la décision plutôt que de la contredire.

**L'AUC de 0,970 est un signal d'alerte, pas un succès.** L'importance des variables montre
`hours_remaining` à 56 743 de gain contre 8 341 pour la suivante : le modèle apprend surtout la
relation « temps restant → dépassement », c'est-à-dire **exactement ce que la règle calcule déjà**.
Le facteur non observé du simulateur ne l'a pas assez bridé. L'écart de 0,074 est donc lui-même une
borne haute, obtenue sur un problème plus facile que le vrai.

**Ce que le modèle apporte réellement** se lit dans les variables suivantes : `category` (8 341),
`backlog` (7 579), `hour_of_day` (6 560). Ce sont les trois que la règle ignore, et elles portent
ensemble à peu près autant que le premier écart. C'est cohérent avec la théorie — un ticket urgent
dans une file vide n'a pas le même destin qu'un ticket urgent derrière quarante autres — mais c'est
mesuré sur des règles que j'ai moi-même écrites dans le simulateur.

**La calibration ne gagne pas sa place.** Le Brier passe de 0,0487 (brut) à 0,0504 (calibré) : la
régression isotone **dégrade** légèrement. LightGBM en objectif binaire optimise une log-loss, il
produit donc déjà des probabilités raisonnables ; l'étape de calibration corrigeait un défaut qui
n'existait pas ici. Le code reste en place — il sera nécessaire le jour où l'objectif changera, ou
si un rééquilibrage de classes est introduit — mais **il ne faut pas le présenter comme un gain**.

### Ce qui se dit en soutenance

> J'ai construit le modèle, je l'ai mesuré, et je ne l'ai pas branché. La règle de décision était
> écrite avant les chiffres, l'écart est tombé dans la bande « gain réel mais insuffisant », et je
> m'y suis tenu — y compris parce qu'il manquait six millièmes.

C'est la troisième fois que le pré-enregistrement change une décision dans ce projet : ADR-0005
(reranking désactivé après mesure), ADR-0006 (bandeau rétrogradé plutôt que gardé ou supprimé), et
celui-ci.

## Alternatives écartées

- **Ne rien livrer** faute de vérité terrain. Aurait laissé `sla_due_at` vide une semaine de plus et
  privé la file de son tri le plus utile, alors que la règle suffit à le rendre.
- **Entraîner sur des labels générés et présenter l'AUC comme un résultat.** C'est l'option qui
  produit le plus beau chiffre et la plus mauvaise ingénierie. Elle est écartée explicitement pour
  que le refus soit traçable.
- **Régression logistique plutôt que LightGBM.** Naturellement calibrée, ce qui supprimerait
  l'étape 5. Écartée parce que l'effet attendu de l'encombrement de la file est un **seuil** (« au
  delà de N tickets en attente, tout ralentit »), et qu'un modèle linéaire ne peut pas le
  représenter sans découpage manuel des variables — c'est-à-dire sans réintroduire à la main ce
  qu'un arbre trouve seul.
- **Calculer le risque à la lecture, sans le stocker.** Toujours frais, jamais triable en SQL. Le
  tri est la fonction principale de cette colonne.
