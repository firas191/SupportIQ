# Baselines de référence — test set gelé (S3-J1)

- Jeu de test : **300 tickets** gelés (`eval/datasets/test.jsonl`), jamais vus.
- Jeu d'entraînement : 774 tickets synthétiques.
- **Détection de langue FR/EN (heuristique)** : exactitude **98.0%** sur le test.

> Métrique principale : **macro-F1** (moyenne des F1 par classe, insensible au déséquilibre).

## Synthèse (macro-F1 par tête)

| Tête | Majorité | TF-IDF+SVC | LLM 0-shot |
|---|---|---|---|
| category | 0.07 | 0.91 | 0.86 |
| priority | 0.17 | 0.40 | 0.36 |
| sentiment | 0.07 | 0.45 | 0.62 |

### Tête : category

| Classe | F1 Majorité | F1 TF-IDF+SVC | F1 LLM 0-shot | Support |
|---|---|---|---|---|
| TECHNIQUE | 0.33 | 0.94 | 0.83 | 60 |
| FACTURATION | 0.00 | 0.96 | 0.86 | 60 |
| COMPTE | 0.00 | 0.95 | 0.86 | 60 |
| RECLAMATION | 0.00 | 0.89 | 0.90 | 60 |
| DEMANDE | 0.00 | 0.82 | 0.86 | 60 |

**Matrice de confusion — TF-IDF+SVC** (gold en lignes) :

| gold ↓ / pred → | TECHNIQUE | FACTURATION | COMPTE | RECLAMATION | DEMANDE |
|---|---|---|---|---|---|
| **TECHNIQUE** | 57 | 0 | 1 | 0 | 2 |
| **FACTURATION** | 0 | 58 | 1 | 1 | 0 |
| **COMPTE** | 1 | 0 | 56 | 0 | 3 |
| **RECLAMATION** | 1 | 0 | 0 | 54 | 5 |
| **DEMANDE** | 2 | 3 | 0 | 6 | 49 |

**Exemples d'erreurs — TF-IDF+SVC** :

| gold | prédit | texte |
|---|---|---|
| TECHNIQUE | DEMANDE | Erreur de stock sur un produit  Je voudrais signaler une erreur dans le système qui n'affi |
| TECHNIQUE | COMPTE | Order status updates not displayed  When I log into my account, the status updates for my  |
| TECHNIQUE | DEMANDE | Website is a disaster!!  THIS WEBSITE IS UNUSABLE!!! I've been trying to purchase a produc |
| FACTURATION | RECLAMATION | FAUX REMBOURSEMENT!  JE VIENS DE TRÈS MALheureusement de recevoir un faux remboursement !! |
| FACTURATION | COMPTE | Facturation incorrecte  Je m'appelle Pierre Dupont et j'ai réalisé un achat pour 500 euros |
| COMPTE | DEMANDE | Merci pour l'aide en urgence  Super rapide ! Je vous remercie pour votre aide, j'ai mainte |

### Tête : priority

| Classe | F1 Majorité | F1 TF-IDF+SVC | F1 LLM 0-shot | Support |
|---|---|---|---|---|
| LOW | 0.00 | 0.40 | 0.31 | 96 |
| MEDIUM | 0.51 | 0.43 | 0.37 | 102 |
| HIGH | 0.00 | 0.36 | 0.40 | 102 |

**Matrice de confusion — TF-IDF+SVC** (gold en lignes) :

| gold ↓ / pred → | LOW | MEDIUM | HIGH |
|---|---|---|---|
| **LOW** | 40 | 30 | 26 |
| **MEDIUM** | 31 | 47 | 24 |
| **HIGH** | 31 | 38 | 33 |

**Exemples d'erreurs — TF-IDF+SVC** :

| gold | prédit | texte |
|---|---|---|
| MEDIUM | HIGH | Site en panne depuis hier  Je ne peux pas accéder à mon compte depuis hier, pourriez-vous  |
| HIGH | MEDIUM | Problème avec le site en ce moment  J'ai essayé de me connecter mais il y a un message d'e |
| LOW | HIGH | Site accessible  Merci de nous avoir réparé le site, il est maintenant accessible sans pro |
| LOW | MEDIUM | La page de produits indisponible  La page de produits est inaccessible car une erreur 500  |
| MEDIUM | LOW | Problème de navigation  Je ne sais pas comment vous vous y prenez, mais je n'ai pas trouvé |
| HIGH | LOW | Page de paiement indisponible  Le site ne charge pas la page de paiement. |

### Tête : sentiment

| Classe | F1 Majorité | F1 TF-IDF+SVC | F1 LLM 0-shot | Support |
|---|---|---|---|---|
| NEG | 0.00 | 0.51 | 0.70 | 117 |
| NEU | 0.00 | 0.46 | 0.58 | 146 |
| POS | 0.22 | 0.39 | 0.59 | 37 |

**Matrice de confusion — TF-IDF+SVC** (gold en lignes) :

| gold ↓ / pred → | NEG | NEU | POS |
|---|---|---|---|
| **NEG** | 58 | 26 | 33 |
| **NEU** | 49 | 54 | 43 |
| **POS** | 3 | 7 | 27 |

**Exemples d'erreurs — TF-IDF+SVC** :

| gold | prédit | texte |
|---|---|---|
| NEG | POS | Site en panne depuis hier  Je ne peux pas accéder à mon compte depuis hier, pourriez-vous  |
| NEU | NEG | Problème avec la navigation  Il y a une bug dans la navigation, certaines pages ne sont pa |
| POS | NEU | Site accessible  Merci de nous avoir réparé le site, il est maintenant accessible sans pro |
| NEU | NEG | Page de paiement indisponible  Le site ne charge pas la page de paiement. |
| NEU | POS | Erreur de stock sur un produit  Je voudrais signaler une erreur dans le système qui n'affi |
| NEU | NEG | Erreur de navigation  L'erreur de navigation sur votre site est toujours présente. |
