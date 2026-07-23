# Fine-tuning (offline, Colab GPU)

Entraînement des modèles de triage, hors du runtime du service. Le service (`ai-service`) ne
consomme que l'artefact **ONNX** produit ici.

## `finetune_xlmr.ipynb` — S3-J2

Fine-tuning de **XLM-RoBERTa-base** en **multi-têtes** (un encodeur partagé + 3 têtes :
catégorie / priorité / sentiment), puis export **ONNX** pour inférence CPU rapide.

### Prérequis
- Un compte Google (Colab). GPU T4 gratuit suffit.
- Les datasets `train.jsonl` + `test.jsonl` (générés en S2-J5). **`train.jsonl` n'est pas dans Git**
  (gitignoré, trop lourd) → il faut l'uploader à la main dans Colab.

### Étapes
1. Ouvre `finetune_xlmr.ipynb` dans Google Colab.
2. `Exécution > Modifier le type d'exécution > GPU (T4)`.
3. Exécute les cellules de haut en bas :
   - **Cellule 2** : uploade `train.jsonl` et `test.jsonl`.
   - **Cellule 5** : entraînement (~quelques minutes sur T4).
   - **Cellule 6** : macro-F1 par tête **vs baselines S3-J1** (le juge de réussite du jour).
   - **Cellules 7-8** : export ONNX + vérification de parité (les F1 ONNX doivent coller à ceux de PyTorch).
   - **Cellule 9** : télécharge `triage_model.zip`.
4. Dézippe `triage_model.zip` dans `ml/artifacts/` (gitignoré). L'intégration au pipeline
   (`app/pipeline/triage.py` + routeur de confiance) est faite en **S3-J3**.

### Critère de succès (rapport §9)
Le modèle fine-tuné doit **battre la baseline TF-IDF** (`eval/results/baseline_s3j1.md`), surtout sur
**priorité** et **sentiment** (la catégorie est déjà à 0.91, l'objectif y est de ne pas régresser).
Ces chiffres alimentent **ADR-0003** (fine-tuning vs baseline).
