# Vérification de la semaine 7 (et du reliquat S6)

Tout le code des jours S7-J1 à S7-J5 a été écrit sans qu'aucun test ne soit exécuté de mon côté
(sandbox Linux indisponible toute la semaine). Ce document est la liste de ce qui reste à prouver,
dans l'ordre où le faire.

**Compter ~2 h**, dont une bonne partie d'attente (téléchargement de modèles, compilation numba,
analyse d'un corpus).

---

## ⚠ À lire avant de commencer — trois pièges qui coûtent une soirée

### 1. Le corpus actuel est daté de janvier 2026

L'ancien générateur écrivait « un ticket par minute à partir du 1ᵉʳ janvier 2026 ». Or **tous** les
écrans de la semaine 7 regardent une fenêtre glissante :

| Écran | Fenêtre |
|---|---|
| Sujets émergents | 14 derniers jours |
| Anomalies de volume | 336 dernières heures |
| Risque SLA | tickets ouverts, échéance relative à `created_at` |

Avec le corpus actuel, **ces trois écrans seront vides** et tu croiras à une panne. Le générateur a
été refait au S7-J5 : il produit maintenant une fenêtre glissante qui se termine aujourd'hui, avec
un rythme jour/nuit. **Il faut régénérer et réimporter.**

### 2. Vérifier que le modèle de triage est déployé AVANT d'importer

```powershell
dir ml\artifacts
# doit contenir triage_xlmr.onnx et triage_tokenizer/
```

S'il manque, `classify()` renvoie `None` et **chaque ticket importé part en escalade LLM**. Sur
3 000 tickets, c'est le quota Groq de la journée en quelques minutes. Si l'artefact n'est pas là,
importe 300 tickets, pas 3 000.

### 3. `mvn verify` échoue en local, et ce n'est pas ton code

Testcontainers 1.20.4 est incompatible avec Docker Engine 29 (bug amont). Les tests d'intégration
Java **passent en CI** et échouent sur ton poste. La compilation, elle, doit réussir : c'est ça
qu'on regarde en local.

---

## Étape 0 — Mise à niveau (une seule fois)

Six migrations sont en attente : **V13 → V18**.

```powershell
# Le service IA a 6 nouvelles dependances lourdes cette semaine (umap-learn, statsmodels,
# lightgbm, python-docx, pytesseract, pillow) + tesseract-ocr dans l'image.
# `--force-recreate` est OBLIGATOIRE : `--build` seul ne redemarre pas le conteneur (cf. CLAUDE.md §7).
docker compose up -d --build --force-recreate ai-service backend

# Verifier que les migrations sont passees
docker compose exec postgres psql -U supportiq -d supportiq -c `
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 8;"
```

**Attendu** : V13 à V18, `success = t` pour toutes.

```powershell
# Sante du service IA
curl http://localhost:8001/health/ready
```

**Attendu** : `database: up`, `insight_readonly: up`, `llm_circuits: {}`.

---

## Étape 1 — Tests automatisés

### Python

```powershell
docker compose exec ai-service pytest -q
docker compose exec ai-service ruff check app tests
```

**Attendu** : ~185 tests verts (144 avant la semaine 7, + 4 nouveaux fichiers). Le test
`test_three_separated_groups_are_found` est marqué `slow` et prend ~30 s (compilation JIT d'UMAP au
premier appel) — c'est normal, et il est exécuté par défaut à dessein.

Si `pytest` répond « No module named pytest » : `docker compose exec ai-service pip install -q -r
/srv/requirements-dev.txt` (le chemin exact dépend du Dockerfile).

### Java

```powershell
mvn -f backend/pom.xml clean compile   # doit reussir
mvn -f backend/pom.xml verify          # 10 erreurs Testcontainers attendues en local
```

Les tests unitaires purs, eux, **doivent** passer même en local :

```powershell
mvn -f backend/pom.xml test -Dtest=EmailCleanerTest
```

**Attendu** : 9 tests verts. C'est le seul livrable du J4 entièrement vérifiable hors Docker.

### Frontend

```powershell
cd frontend
npm run build
```

**Attendu** : build AOT vert. C'est ce qui valide `strictTemplates` sur les trois nouveaux écrans
(sujets, alertes, documents) et la colonne SLA.

### CI

Pousse la branche : les 4 jobs (`ai-service`, `backend`, `frontend`, `eval`) doivent être verts.
**C'est la CI qui fait foi pour les tests d'intégration Java.**

---

## Étape 2 — Reconstruire un corpus exploitable

```powershell
# 3 000 tickets sur ~14 jours glissants, avec rythme jour/nuit
python scripts/generate_sample_csv.py 3000 samples/tickets_recent.csv
```

Puis, dans l'interface : **Imports** (compte ADMIN) → choisir le fichier → vérifier le mapping →
Confirmer.

```powershell
# Laisser l'analyse asynchrone se derouler, puis controler :
docker compose exec postgres psql -U supportiq -d supportiq -c `
  "SELECT (SELECT COUNT(*) FROM tickets) tickets, (SELECT COUNT(*) FROM analyses) analyses;"
```

**Attendu** : `analyses` rejoint `tickets` (aux tickets pré-existants près). Si l'écart stagne,
regarde la file RabbitMQ (`http://localhost:15672`) et les logs `ai-service`.

```powershell
# Embeddings : indispensables pour les sujets emergents (etape 3).
# Le premier appel telecharge e5 (~1 Go) si le cache est vide.
curl -X POST http://localhost:8001/embeddings/backfill
```

---

## Étape 3 — S7-J1 : sujets émergents

### En ligne de commande

```powershell
# Declenchement direct (le premier appel compile UMAP avec numba : plusieurs minutes)
curl -X POST http://localhost:8001/topics/detect -H "Content-Type: application/json" -d "{}"
```

**Attendu** : `{"window_days":14,"analysed":<proche de 3000>,"topics":<1 à 20>}`.

**Le chiffre à regarder est `analysed`.** S'il vaut 0 ou une poignée, ce ne sont pas les sujets qui
manquent — ce sont les embeddings ou les dates. Zéro sujet sur 3 000 tickets analysés est un
résultat légitime ; zéro sujet sur 12 tickets ne veut rien dire.

```powershell
docker compose exec postgres psql -U supportiq -d supportiq -c `
  "SELECT label, size, recent_count, previous_count, growth, top_category FROM topics
   WHERE computed_at = (SELECT MAX(computed_at) FROM topics) ORDER BY growth DESC NULLS LAST;"
```

### Dans l'interface

1. Connecte-toi en **ADMIN ou MANAGER** (l'écran est refusé aux AGENT).
2. Menu latéral → **Sujets émergents**.
3. Si la page dit « Aucun calcul pour l'instant » → clique **Recalculer** (plusieurs minutes).

**À vérifier point par point :**

| Ce que tu dois voir | Pourquoi ça compte |
|---|---|
| Un libellé en français lisible (« Double débit sur carte »), pas « Groupe 3 » | L'étiquetage LLM fonctionne. Si tu vois des sujets de tickets bruts, c'est le **repli** — le modèle était indisponible. |
| Sur un sujet nouveau : la pastille **« Nouveau »**, pas « +100 % » | `growth = NULL` traverse correctement toute la chaîne. |
| Sous chaque sujet : « X sur la période récente, Y avant » | Les deux moitiés sont affichées, pas seulement leur rapport. |
| Les numéros de ticket sont **cliquables** et ouvrent la fiche | Le libellé est une interprétation ; il doit être vérifiable en un clic. |
| La date de calcul est visible en haut | L'instantané date de la nuit, pas de maintenant. |

**Test négatif** : connecte-toi avec un compte AGENT → l'entrée « Sujets émergents » ne doit pas
apparaître dans le menu, et `/topics` doit rediriger vers `/tickets`.

---

## Étape 4 — S7-J2 : anomalies de volume

### Vérifier d'abord que l'historique est exploitable

```powershell
docker compose exec postgres psql -U supportiq -d supportiq -c `
  "SELECT bucket, category, tickets FROM v_hourly_volume ORDER BY bucket DESC LIMIT 20;"
```

**Attendu** : des heures récentes avec des volumes qui varient. Si tout est concentré sur une heure,
le détecteur refusera de conclure — c'est le comportement voulu, mais la démo sera impossible.

### Injecter un pic

Le livrable du §9 est « alerte déclenchée sur injection d'un pic simulé ». Envoie une vingtaine de
tickets de la même catégorie via le webhook, dans l'heure en cours :

```powershell
$key = "supportiq-webhook-key"
$secret = "supportiq-webhook-secret"   # tes valeurs du .env

1..25 | ForEach-Object {
  $body = "{`"external_ref`":`"SPIKE-$_`",`"subject`":`"Double debit sur ma carte`",`"body`":`"Le paiement a ete preleve deux fois, dossier $_.`",`"language`":`"fr`"}"
  $hmac = New-Object System.Security.Cryptography.HMACSHA256
  $hmac.Key = [Text.Encoding]::UTF8.GetBytes($secret)
  $sig = ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($body)) | ForEach-Object { $_.ToString("x2") }) -join ""
  Invoke-RestMethod -Uri http://localhost:8080/api/webhooks/tickets -Method Post `
    -Headers @{ "X-Api-Key" = $key; "X-Signature" = $sig; "Content-Type" = "application/json" } `
    -Body $body | Out-Null
}
Write-Host "25 tickets injectes"
```

⚠ **L'heure en cours est exclue de la mesure** (elle est incomplète par construction). Il faut donc
soit attendre le passage à l'heure suivante, soit injecter le pic peu avant une heure ronde.

### Dans l'interface

1. **Tableau de bord** (MANAGER+).
2. En haut, au-dessus des KPI, clique **Vérifier maintenant**.

**À vérifier :**

| Ce que tu dois voir | Pourquoi |
|---|---|
| Un panneau avec « **41 tickets, contre 6 attendus** » | Les chiffres, pas le score. « Score 7,2 » ne veut rien dire pour qui doit décider. |
| Le score et la méthode (`stl`/`seasonal_median`) **en info-bulle** au survol | Vérifiable sans encombrer. |
| Un bouton **« Je m'en charge »** | Acquittement. |
| Après clic : l'alerte reste visible, avec ton adresse | Prise en charge ≠ effacée : sinon deux personnes traitent le même incident. |
| Un second clic sur la même alerte → message « quelqu'un vient de la prendre » | 409 côté serveur, pas un succès silencieux. |
| Sans alerte : **une seule ligne discrète**, pas un bandeau | Une alerte ne se remarque que si l'emplacement est calme le reste du temps. |

**Test du temps réel** : ouvre le tableau de bord dans deux onglets, acquitte dans l'un → l'autre
doit se mettre à jour seul (WebSocket `/topic/alerts`, déclaré au S4-J5 et alimenté pour la
première fois).

**Test d'idempotence** : reclique **Vérifier maintenant** → aucune nouvelle alerte, message
« rien de nouveau à signaler ». C'est la contrainte `UNIQUE(type, scope, bucket_start)` qui joue.

**Test de disparition** : `GET http://localhost:8080/api/dashboard/alerts` doit renvoyer **404** —
la route a été déplacée vers `/api/alerts`.

---

## Étape 5 — S7-J3 : risque SLA

### Entraînement (le seul livrable qui demande une décision de ta part)

```powershell
pip install lightgbm
python ml/train_sla_risk.py
```

**Ce qui s'affiche :**

```
AUC modele    : 0.xxx
AUC baseline  : 0.xxx   (part du budget consommee)
Ecart         : +0.xxx
```

**Applique l'ADR-0010 telle qu'elle est écrite, sans la réinterpréter :**

| Écart d'AUC | Décision |
|---|---|
| ≥ 0,08 | Déployer : copier `ml/artifacts/sla_risk.txt` et `sla_calibration.json` (déjà écrits par le script) et recréer `ai-service`. |
| 0,03 – 0,08 | **Ne pas déployer.** Supprimer les deux artefacts. |
| < 0,03 | Ne pas déployer, clore la piste. |

Puis passe l'ADR en *accepté* avec les chiffres réels. **C'est le point le plus intéressant à
raconter en soutenance** — la règle a été écrite avant les chiffres, exactement comme pour le
reranking (ADR-0005) et le juge (ADR-0006).

Rappel du caveat à énoncer : *l'AUC mesure la capacité de LightGBM à retrouver les règles de mon
simulateur ; elle ne dit rien de sa performance sur des tickets réels.*

### Scoring

```powershell
curl -X POST http://localhost:8001/sla/score
```

**Attendu** : `{"scored":<N>,"model":"rules" ou "lightgbm","at_risk":<N>}`.

Le champ `model` est la vérification qui compte : s'il dit `lightgbm` alors que tu n'as pas déployé
l'artefact, quelque chose ne va pas.

### Dans l'interface

1. **Tickets**.
2. Colonne **« Échéance »** à droite de « Client ».

| Ce que tu dois voir | Pourquoi |
|---|---|
| **Serré / À surveiller / Confortable**, jamais « 62 % » | Le pourcentage suggère une précision que le modèle n'a pas. |
| Au survol : « Risque de dépassement 62 % · rules · calculé le … » | Chiffre exact, **provenance** et **date** — le score vieillit. |
| Un ticket non scoré affiche « — » et **reste dans la liste** | Jointure externe : ne jamais masquer les tickets récents. |
| Clic sur l'en-tête « Échéance » → tri | Les non scorés doivent aller **en bas** (`NULLS LAST`). |
| Bouton **« À risque »** à côté des onglets de statut | Filtre booléen, pas un seuil réglable. |
| L'URL contient `atRisk=true` | La recherche reste partageable. |

**Le test qui compte vraiment** : trie par échéance en décroissant et vérifie que **les tickets sans
score ne sont pas en tête**. C'est le tri le plus dangereux — mettre en avant ce dont on ne sait
rien.

---

## Étape 6 — S7-J4 : ingestion documentaire

### Préparer un document de test

Crée un `.txt` (le format le plus simple à contrôler), avec 3 demandes distinctes :

```
Demande 1
De : alice@example.com
Bonjour, ma commande 48219 n'est jamais arrivee malgre le suivi qui indique une livraison.
Merci de me dire ce qui s'est passe.

Demande 2
De : bob@example.com
Hello, I was charged twice for order 77120 on March 3rd. Please refund the duplicate payment.

Demande 3
Je n'arrive plus a me connecter a mon compte depuis la mise a jour de mardi.
Mon identifiant est client-90211.
```

### Dans l'interface

1. Connecte-toi en **AGENT** (l'écran est volontairement ouvert aux AGENT+, contrairement aux
   Imports réservés aux ADMIN — c'est un point à savoir défendre).
2. Menu → **Documents**.
3. Dépose le fichier.

| Ce que tu dois voir | Pourquoi |
|---|---|
| **3 fiches**, une par demande | Le découpage LLM fonctionne. |
| Le sujet et le corps proviennent du texte, **mot pour mot** | C'est le contrôle d'ancrage : une entrée reformulée est rejetée. |
| Les adresses `alice@` et `bob@` récupérées | Extraction déterministe par regex. |
| Sur la demande 3 (sans adresse) : le champ surligné **« À vérifier »** | Confiance par champ. Le champ est signalé, pas le ticket entier. |
| Tous les champs sont modifiables | Corriger vaut mieux qu'écarter. |
| **« Écarter »** retire une fiche en un clic | Un document contient souvent des en-têtes que le modèle prend pour une demande. |
| **« Créer les 3 tickets »** → redirection vers Tickets | Les tickets partent aussitôt en analyse. |

**Test du PDF** (le livrable du §9 est « un PDF de 12 demandes ») : exporte le même texte en PDF
depuis Word, dépose-le. Résultat identique attendu.

**Test de l'OCR** : imprime le PDF en image (ou scanne une page) et dépose-le. Tu dois voir la
mention discrète « lu par reconnaissance de texte ». Si Tesseract manque dans l'image Docker, le
lot revient vide — vérifie alors :

```powershell
docker compose exec ai-service tesseract --version
```

**Test de format refusé** : dépose un `.zip` → message « ce format n'est pas pris en charge »
(415), pas une erreur générique.

### IMAP (optionnel, ~10 min)

Le connecteur est **désactivé par défaut** — c'est intentionnel : mal configuré, il vide une vraie
boîte en la marquant lue. Pour l'essayer sans risque, utilise une boîte jetable, ou saute cette
partie et démontre le pipeline documentaire.

```
INTAKE_EMAIL_ENABLED=true
INTAKE_EMAIL_HOST=imap.exemple.fr
INTAKE_EMAIL_USER=...
INTAKE_EMAIL_PASSWORD=...
```

Envoie-toi un courriel avec une réponse citée et une signature, attends 2 minutes, puis vérifie que
le ticket créé contient **le message et rien d'autre** :

```powershell
docker compose exec postgres psql -U supportiq -d supportiq -c `
  "SELECT subject, left(body, 200) FROM tickets WHERE source = 'EMAIL' ORDER BY id DESC LIMIT 3;"
```

Le sujet ne doit plus porter « Re: » ni « Fwd: », et le corps ne doit contenir ni les lignes `>` ni
la signature.

---

## Étape 7 — S7-J5 : charge et résilience

Protocole complet dans `perf/README.md`. En résumé :

```powershell
python scripts/generate_sample_csv.py 50000 samples/tickets_50k.csv
# import via /imports, puis :
docker compose exec postgres psql -U supportiq -d supportiq -c "ANALYZE tickets; ANALYZE analyses;"
```

⚠ **50 000 tickets = 50 000 analyses.** Vérifie que le modèle ONNX est bien déployé, sinon tu
épuises ton quota. Alternative : désactive temporairement le consommateur (`docker compose stop
ai-service`) — le test de charge porte sur les endpoints de lecture, pas sur l'analyse.

Puis les plans avant/après V18, les deux tirs k6, et le scénario de kill. **Remplis
`eval/results/perf_s7j5.md`** — il est commité vide.

**Le verdict à ne pas esquiver** : si le plan de la requête filtrée par statut ne change pas avec
`ix_tickets_status_created`, **retire l'index**. Il se paie à chaque écriture sur la table la plus
écrite du projet.

---

## Reliquat des jours précédents

| Jour | Ce qui reste | Comment |
|---|---|---|
| S6-J5 | **Démo 6** : mettre une clé Groq invalide dans `.env`, recréer `ai-service`, poser une question Insight → les 2 premiers appels échouent, le circuit s'ouvre, la réponse arrive via Gemini/OpenRouter. `curl /health/ready` montre `llm_circuits` ouverts, et `SELECT degraded FROM agent_runs ORDER BY id DESC LIMIT 1;` vaut `t`. |
| Demi-journée S6 | **Envoi réel** : `REPLY_ENABLED=true` + `SPRING_MAIL_HOST=mailpit`, valider un brouillon sur un ticket ayant un `customer_email` → message visible sur `http://localhost:8025`. |

---

## Dettes connues (à faire avant la soutenance, pas ce soir)

1. **Aucun test ne couvre le chemin nominal des 8 clients HTTP.** Les tests d'intégration pointent
   tous vers `localhost:1` pour vérifier la *dégradation* ; personne ne vérifie qu'un appel réussi
   part correctement. C'est ce trou qui a laissé passer deux clients cassés au S6-J3.
   `MockRestServiceServer` fermerait le sujet en ~1 h.
2. **Quatre exceptions à migrer** vers `common/error/AiServiceException`, créée au S7-J1 et déjà
   utilisée par `topics`, `alerts`, `sla` et `intake`.
3. **Le wrapper Maven** (`mvn -N wrapper:wrapper`) n'est toujours pas commité.
4. **Testcontainers** à épingler sur une version compatible Docker 29, pour retrouver la boucle de
   test locale.
