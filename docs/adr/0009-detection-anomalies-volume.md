# ADR-0009 — Détection d'anomalies de volume : désaisonnaliser, puis juger avec un estimateur robuste

- **Statut** : accepté
- **Date** : S7-J2
- **Contexte** : rapport §9 Semaine 7 — « Détection d'anomalies de volume : décomposition STL par
  catégorie + z-score robuste ; création d'`alerts` + push WebSocket + panneau d'alertes UI avec
  acquittement. »

## Problème

Un détecteur d'anomalies a deux façons d'être inutile, et la seconde est bien pire que la première.

**Rater un pic** coûte une occasion manquée. **Crier au loup** coûte la fonctionnalité entière : le
premier réflexe devant un flux d'alertes sans intérêt est de cesser de les lire, et les vraies
partent avec. La conception doit donc être organisée autour des faux positifs, pas autour de la
sensibilité.

Trois sources de faux positifs, toutes présentes ici :

1. **Le rythme normal.** Le volume de tickets suit un cycle marqué : nuits vides, matinées
   chargées. Un score calculé sur les comptes bruts placerait chaque matinée à plusieurs écarts de
   la moyenne journalière — une alerte quotidienne à heure fixe.
2. **Le pic qui se cache lui-même.** L'anomalie recherchée fait partie de l'échantillon qui sert à
   définir la normale. Un écart-type est déplacé par un seul point extrême.
3. **Les petits nombres.** Sur une catégorie qui reçoit un ticket par semaine, passer à deux est un
   doublement — statistiquement spectaculaire, opérationnellement sans intérêt.

## Décision

**Désaisonnaliser d'abord, juger ensuite avec un estimateur à point de rupture élevé, et refuser de
conclure quand les nombres sont trop petits.**

### 1. Pas horaire, période 24

La mesure porte sur des **heures**, avec une saisonnalité de période 24. Deux semaines d'historique
donnent 336 points, soit 14 observations par phase horaire.

Le pas journalier avec une saisonnalité hebdomadaire (période 7) aurait demandé plusieurs mois
d'historique pour estimer la même chose, et un pic ne serait visible qu'en fin de journée.
Contrepartie assumée : **l'effet jour-de-la-semaine n'est pas retiré** ; il est partiellement
absorbé par la tendance de la décomposition.

### 2. STL robuste, avec un repli sans dépendance

`statsmodels.tsa.seasonal.STL(period=24, robust=True)`. L'option `robust` n'est pas un détail :
sans elle, un pic passé déforme la forme saisonnière estimée, donc la « normale » de cette heure-là,
donc la capacité à détecter le pic suivant au même moment. C'est la même préoccupation que le MAD en
aval, appliquée une étape plus tôt.

Quand statsmodels est absent, ou quand la série est dégénérée, le détecteur retombe sur une
**médiane par phase horaire** : la valeur attendue à 15 h est la médiane des 15 h passées. C'est
grossier — cela ignore la tendance — mais cela répond à la bonne question et reste médian, donc
insensible aux pics passés. Le repli est **exercé par un test**, pas seulement écrit.

Sous deux périodes complètes d'historique, le détecteur **ne conclut pas**. Une forme saisonnière
n'est pas estimable sur moins.

### 3. Score robuste sur le résidu (MAD, pas écart-type)

*Modified z-score* d'Iglewicz & Hoaglin : `0.6745 · (x − médiane) / MAD`. Seuil 3,5 pour `WARNING`,
6 pour `CRITICAL` (le premier est celui recommandé par les auteurs, le second est un choix du
projet pour séparer « à regarder » de « à traiter maintenant »).

**Le point de rupture est l'argument.** La médiane et le MAD supportent qu'on corrompe jusqu'à 50 %
des points avant de bouger ; la moyenne et l'écart-type, 0 %. Chiffré sur un cas concret (couvert
par un test) : trente points autour de 10 et cinq pics à 200 donnent un z-score classique de **2,45**
pour un pic à 200 — sous le seuil usuel de 3 — parce que ces pics ont gonflé leur propre mesure de
dispersion. Le score robuste vaut **128** sur le même échantillon.

### 4. Deux planchers, qui sont des refus de conclure

- **Plancher absolu** : moins de 8 tickets observés, aucune alerte. Une anomalie sur 3 tickets n'est
  pas une anomalie. Même leçon qu'au digest (S6-J4), où le premier commentaire produit analysait une
  tendance sur un seul ticket.
- **Dispersion minimale** : un MAD inférieur à un demi-ticket ne mesure aucune variabilité réelle.
  Les comptes sont des entiers ; en dessous de cette résolution, on divise soit par zéro, soit par du
  bruit de calcul flottant. Le score renvoyé est alors **0**, ce qui signifie « cette série ne permet
  pas de conclure » — même choix que le `growth = NULL` du S7-J1.

### 5. Les heures vides sont des zéros, pas des absences

La vue `v_hourly_volume` n'a pas de ligne pour une heure sans ticket. La grille est reconstruite
avant tout calcul. Sans cela, la normale d'une catégorie serait calculée sur ses seules heures
actives : une catégorie presque toujours vide paraîtrait d'une régularité parfaite, et ses trois
tickets de 3 h du matin deviendraient une alerte.

### 6. Une seule alerte par anomalie

Le détecteur passe toutes les cinq minutes et redécouvre nécessairement les pics récents. La
contrainte `UNIQUE(type, scope, bucket_start)` (V16) porte **à la fois** l'idempotence et la sûreté
multi-instance — même mécanisme qu'`UNIQUE(week_start)` au digest, pour la même raison : vérifier
« existe-t-elle déjà ? » avant d'insérer ne suffit pas, deux nœuds peuvent lire « non » simultanément.

### 7. Spikes seulement, pour aujourd'hui

Une chute de volume est tout aussi intéressante — un canal d'ingestion cassé la produit — mais elle
n'est **pas détectable de la même façon** : un résidu négatif est borné par la valeur attendue
elle-même (on ne descend pas sous zéro). Sur une catégorie qui attend 3 tickets par heure, aucune
chute ne peut atteindre le seuil. La traiter correctement demande un plancher de *volume attendu*
distinct du plancher de *volume observé*, et une mesure à part. Reportée plutôt que bâclée, avec un
test qui documente le choix.

## Frontière : qui possède l'alerte

Le service IA **mesure** et renvoie des candidates ; Spring **crée**, déduplique, diffuse et gère
l'acquittement.

Ce partage diffère de celui des sujets émergents (S7-J1), où FastAPI écrit sa propre table, et la
différence est délibérée : une alerte porte une **décision humaine attachée à un utilisateur
identifié**. Tout ce qui a un cycle de vie humain vit du côté du plan de contrôle, qui a
l'authentification, le RBAC et les transactions. Un instantané de sujets n'a pas de cycle de vie : il
est calculé, lu, remplacé.

Corollaire : le service IA **ne sait pas** ce qui a déjà été signalé, et ne doit pas le savoir — la
déduplication demande la table.

## Conséquences

- Une catégorie nouvelle n'est surveillée qu'après 48 h d'existence.
- L'effet jour-de-la-semaine peut produire des faux positifs le lundi matin si le pic hebdomadaire
  est très marqué. À mesurer sur des données réelles ; le correctif serait une seconde saisonnalité
  (MSTL), qui coûte en complexité ce qu'elle rapporte en finesse.
- **La qualité de la détection n'est pas évaluée sur données réelles.** Il faudrait un historique
  annoté (« ici il y a eu un incident »), qui n'existe pas. Les tests couvrent le comportement du
  détecteur sur des séries construites dont on connaît la réponse, pas son taux de faux positifs en
  production. C'est la limite honnête de ce livrable, à énoncer telle quelle en soutenance.

## Alternatives écartées

- **Seuils fixes par catégorie** (« alerter au-delà de 30 tickets/heure »). Simples, lisibles, et
  faux dès que le volume du produit change ou que les habitudes se déplacent. Ils demandent un
  réglage manuel par catégorie et par heure — c'est-à-dire exactement le travail que la
  désaisonnalisation automatise.
- **Z-score classique sur le résidu.** Écarté par la mesure ci-dessus : aveuglé par les pics qu'il
  doit détecter.
- **Prophet / ARIMA.** Sur-dimensionnés pour une question binaire (« cette heure est-elle
  anormale ? ») ; ils répondent à une question de *prévision*, dont on n'a pas besoin, et ajoutent
  un modèle à entraîner et à surveiller.
- **Détection sur les tickets individuels plutôt que sur les volumes.** C'est le sujet du S7-J1
  (regroupement) et du S7-J3 (risque SLA). Ici la question porte sur le flux, pas sur son contenu.
