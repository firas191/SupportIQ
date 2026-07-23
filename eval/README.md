# Harness d'évaluation IA & datasets

- `datasets/train.jsonl` — dataset d'entraînement (synthétique + public à venir), **versionné hors Git** (trop lourd).
- `datasets/test.jsonl` — **test set gelé** : ~300 tickets, généré en S2-J5, **versionné dans Git**.
  Règle absolue : jamais utilisé en entraînement, jamais modifié après gel (c'est la référence
  de toutes les évals de la Semaine 3 et le garde-fou anti-régression en CI).

## Génération du dataset (S2-J5)

```bash
python eval/generate_dataset.py --train-n 800 --test-n 300
```

**Aucune installation** : le script n'utilise que la bibliothèque standard (`urllib`). Les API LLM
(Groq/Gemini/OpenRouter) sont compatibles OpenAI, donc un POST HTTP suffit — pas de `litellm`/`pydantic`
à compiler (leurs extensions Rust posent problème sous Windows avec un Python récent).

Les clés API sont lues depuis `.env` (`GROQ_API_KEY` / `GEMINI_API_KEY` / `OPENROUTER_API_KEY`).
La passerelle bascule automatiquement d'un fournisseur à l'autre en cas de quota épuisé —
même chaîne de repli que le service (`ai-service/app/core/llm.py`).

### Espace de labels (miroir de `ai-service/app/schemas.py`)

| Champ | Valeurs |
|---|---|
| `category` | TECHNIQUE, FACTURATION, COMPTE, RECLAMATION, DEMANDE |
| `priority` | LOW, MEDIUM, HIGH |
| `sentiment` | NEG, NEU, POS |
| `language` | fr, en |

### Schéma d'une ligne JSONL

```json
{
  "id": "syn-test-00042",
  "language": "fr",
  "subject": "Double prélèvement sur ma commande",
  "body": "Bonjour, j'ai été débité deux fois pour la commande n°8842...",
  "text": "Double prélèvement...\n\nBonjour, j'ai été débité deux fois...",
  "category": "FACTURATION",
  "priority": "HIGH",
  "sentiment": "NEG",
  "style": "formal",
  "split": "test",
  "source": "synthetic"
}
```

`text` (sujet + corps) est le champ que consommeront les classifieurs de la Semaine 3 ;
`subject`/`body` restent séparés pour d'autres usages.

## Méthodologie — pourquoi c'est fiable

Le rapport prévoit un test set « étiqueté à la main ». Sans annotateurs humains, on obtient la
même fiabilité par deux mécanismes :

1. **Génération conditionnée.** On ne demande pas au LLM d'inventer un ticket *puis* de le
   deviner. On tire d'abord une cible (catégorie, priorité, sentiment, langue, style, longueur)
   et on demande d'écrire un ticket qui *colle* à cette cible. Le label est la **consigne**, pas
   une prédiction : il est correct par construction. Le texte n'a jamais le droit de nommer ses
   labels (sinon un classifieur tricherait).

2. **Filtre d'accord (test set uniquement).** Chaque ticket candidat du test passe un **second
   appel LLM qui le reclasse à l'aveugle** (il ne voit que le texte). On ne le garde que si la
   reclassification concorde avec la consigne sur les champs sous contrôle
   (`category` + `sentiment` par défaut, réglable via `--gate`). Un désaccord = ticket ambigu →
   rejeté, et on régénère jusqu'à compléter chaque cellule. Le taux d'accord priorité est
   *reporté* mais non bloquant (la priorité est plus subjective). Résultat : un test set dont les
   labels sont **doublement validés** (consigne + juge indépendant).

3. **Équilibrage & étanchéité.** Catégories équilibrées sur FR/EN (quota par cellule
   catégorie×langue). Déduplication par texte normalisé. Le **test set est construit en premier**
   et ses textes alimentent l'ensemble `seen`, donc le train ne peut jamais reproduire un ticket
   du test → **aucune fuite train→test**.

**Honnêteté attendue en entretien / soutenance** : ce dataset est *synthétique*. Il valide la
mécanique (pipeline, métriques, comparaisons baseline vs fine-tuning) mais un LLM générateur peut
introduire un style stéréotypé. Plans d'atténuation prévus : mélange avec un corpus public réel
en S3-J1, augmentation ciblée des classes faibles, et relecture manuelle d'un échantillon du test
set (`--only test` puis lecture de `datasets/test.jsonl`).

## Suites d'évaluation (ajoutées au fil des semaines)
1. S3-J5  Classification : precision / recall / F1 par classe — local vs LLM vs hybride
2. S5-J2  Retrieval : recall@5 sur 40 paires question/chunk annotées
3. S5-J5  Brouillons RAG : LLM-as-judge (exactitude, complétude, ton) sur 50 brouillons
4. S6-J2  Text-to-SQL : 30 questions avec SQL de référence, comparaison des résultats
