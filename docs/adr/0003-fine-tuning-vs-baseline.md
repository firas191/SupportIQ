# ADR-0003 — Modèle de triage : fine-tuning XLM-R vs baselines

Date : S3-J2 · Statut : accepté

## Contexte

Le triage (F1) doit prédire trois étiquettes par ticket : **catégorie**, **priorité**, **sentiment**,
en FR et EN. On a mesuré, sur le **test set gelé** (300 tickets, `eval/datasets/test.jsonl`), trois
références (S3-J1) puis le modèle fine-tuné (S3-J2). Macro-F1 :

| Tête | Majorité | TF-IDF+SVC | LLM 0-shot | **XLM-R fine-tuné (v2)** |
|---|---|---|---|---|
| catégorie | 0,07 | 0,91 | 0,86 | **0,95** |
| priorité  | 0,17 | 0,40 | 0,36 | **0,33** |
| sentiment | 0,07 | 0,45 | 0,62 | **0,60** |

Le modèle retenu est un **encodeur `xlm-roberta-base` partagé + 3 têtes linéaires**, entraîné en
multi-tâches (somme des 3 entropies croisées), exporté en **ONNX** (parité vérifiée : 0,95 / 0,33 / 0,60).

**v2 (passe propre)** vs v1 (CLS, 5 epochs → 0,91 / 0,35 / 0,60) : **mean-pooling** masqué au lieu du token
`<s>`, **split de validation** (15 % du train, stratifié — le test gelé n'est jamais touché pour la
sélection), perte sentiment **pondérée**, **best-checkpoint** sur le F1 sentiment en validation. Effets :
catégorie **+0,04** (0,95), et surtout sentiment **rééquilibré** — rappel NEG **0,43 → 0,66** à macro-F1
constant, ce qui compte davantage en support (attraper les mécontents). La validation a aussi révélé un
**sur-apprentissage** après l'epoch 5 (F1 val qui redescend pendant que la loss baisse) — le best-checkpoint
l'a neutralisé.

## Décision

1. **Adopter le XLM-R fine-tuné (ONNX) comme classifieur local** pour **catégorie** et **sentiment** :
   - catégorie : **0,95** (> TF-IDF 0,91) grâce au mean-pooling, obtenue via l'encodeur partagé ;
   - sentiment : **+0,15 vs TF-IDF** et à parité avec le LLM (0,60 vs 0,62), **sans coût ni latence LLM**,
     avec un rappel NEG bien meilleur (0,66) — pertinent pour prioriser les clients mécontents.
2. **Ne pas apprendre la priorité.** Ni TF-IDF (0,40) ni le fine-tuning (0,35) ne battent significativement
   le hasard, et la confusion est diffuse : **le signal n'est pas dans le texte**. La priorité sera
   **dérivée par règles** (mots-clés d'urgence, catégorie, sentiment, SLA) — décision assumée et honnête,
   plutôt que d'exposer un modèle à 0,35.
3. **Routage par confiance (S3-J3).** Le modèle local sert les cas sûrs ; on **escalade au LLM** seulement
   quand la confiance locale est faible. Justifié empiriquement : le LLM ne domine que sur le nuancé
   (et le fine-tuning a déjà comblé l'essentiel de l'écart en sentiment), donc l'escalade doit rester rare.

## Justification

- **Chiffré, pas dogmatique.** On ne fine-tune pas « parce que c'est mieux » : la catégorie ne gagne rien
  (tâche lexicale, TF-IDF suffit), la priorité ne gagne rien non plus (signal absent), seul le sentiment
  justifie le transformer. La décision suit les mesures classe par classe, pas une accuracy globale.
- **Coût/latence.** Un seul ONNX CPU (~30 ms) couvre catégorie + sentiment à 0 $. Réserver le LLM aux cas
  incertains borne la facture et la latence.
- **Honnêteté.** Reconnaître que la priorité n'est pas apprenable du texte est plus solide en soutenance
  que d'afficher un F1 médiocre en le présentant comme un modèle.

## Conséquences

+ Inférence locale rapide et gratuite pour les deux têtes utiles ; un seul artefact à déployer.
+ Point de comparaison gelé conservé (`eval/results/baseline_s3j1.md`) pour toute itération future.
- La priorité dépend d'un moteur de règles à écrire (S3-J3+) — dette assumée et tracée.
- Dataset **synthétique** : ces F1 sont peut-être optimistes vs des tickets réels bruités. À re-mesurer si
  un corpus réel arrive (augmentation prévue S3-J1 bis).
- Sentiment encore perfectible (NEG rappel 0,43, POS classe faible à 37) → cible d'augmentation ciblée.

## Porte de sortie

Si un corpus réel dégrade la catégorie sous TF-IDF, on peut revenir à TF-IDF pour cette tête (l'encodeur
partagé n'interdit pas un classifieur externe). Si la règle de priorité plafonne, on rouvrira la question
d'un modèle dédié avec des features hors-texte (heure, backlog, historique client).

## Liens

- ADR-0002 (état frontend). Baselines : `eval/results/baseline_s3j1.md`. Notebook : `ml/finetune_xlmr.ipynb`.
- ADRs suivants : 0004 seuil du routeur de confiance (S3-J3), 0005 retrieval hybride RRF, 0006 guardrails Text-to-SQL.
