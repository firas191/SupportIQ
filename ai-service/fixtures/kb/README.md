# Corpus de démonstration — base de connaissances

Documents **fictifs mais réalistes** décrivant le support d'une boutique en ligne imaginaire,
« Novéa ». Ils servent à alimenter la base de connaissances (S5-J1) puis à générer des brouillons
de réponse cités (S5-J3).

## Pourquoi ces documents précisément

Le corpus est **aligné sur les catégories du triage** (`FACTURATION`, `COMPTE`, `TECHNIQUE`,
`RECLAMATION`, `DEMANDE`) et sur le vocabulaire du jeu de tickets synthétiques généré en S2-J5.
C'est la condition pour que le retrieval trouve réellement quelque chose : une base de connaissances
qui parle d'un autre produit que les tickets ne prouve rien, elle donne juste l'illusion d'un RAG.

## Honnêteté

Ce corpus est écrit à la main, pas extrait d'une vraie documentation client. Les procédures, délais
et montants sont inventés — ils sont cohérents entre eux, ce qui suffit pour évaluer la mécanique
(recall@k en S5-J2, LLM-as-judge en S5-J5), mais les chiffres de qualité seraient à re-mesurer sur
une documentation réelle.

## Chargement

Écran **Base de connaissances** (réservé aux administrateurs) → déposer les fichiers `.md`.
