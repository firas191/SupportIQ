# CLAUDE.md — Mémoire permanente du projet SupportIQ

> Ce fichier est la mémoire de travail pour **toutes** les sessions.
> La **source de vérité absolue** reste `SupportIQ_Rapport_Technique.md` à la racine :
> architecture, features, modèle de données, contrats d'API, planning jour par jour,
> conventions. En cas de doute ou de conflit, le rapport prime — le relire avant d'agir.

---

## 0. Position courante dans le planning

- **Semaine 1 — Jour 1 (bootstrap) : FAIT.** Squelette §15 recréé à la racine, `CLAUDE.md`
  généré, config Docker revue. `git init` + commit à faire par firas sur Windows (le sandbox
  ne peut pas : le mount interdit `unlink`/`rename`, indispensables à git).
- **Semaine 1 — Jour 2 (backend Spring) : CODE LIVRÉ, vérif en attente.** `backend/` :
  pom Boot 3.4.1/Java 21, profils dev/prod, Flyway `V1__users_auth.sql` (users, refresh_tokens),
  entités JPA (User, RefreshToken, Role) + repositories, `@RestControllerAdvice` ProblemDetail.
  Job CI `backend` activé (`mvn -B verify`). Sécurité **différée au J3** (évite un permit-all
  jetable). **À vérifier par firas** : `docker compose up -d postgres` puis `mvn spring-boot:run`
  → `curl localhost:8080/actuator/health` = `{"status":"UP"}`, Flyway applique V1.
- **Semaine 1 — Jour 3 (auth) : CODE LIVRÉ, vérif en attente.** Spring Security stateless :
  JWT access 15 min (jjwt HS256) + refresh rotatif 7 j (opaque, hashé SHA-256, révocable),
  BCrypt 12, RBAC hiérarchique ADMIN>MANAGER>AGENT (RoleHierarchy + @PreAuthorize).
  Endpoints `/api/auth/register` (ADMIN), `/login`, `/refresh` (rotation), `/logout`, `/me`.
  Premier ADMIN amorcé par `AdminSeeder` (idempotent, via env). Entrypoints 401/403 en
  ProblemDetail. Suite Testcontainers `AuthIntegrationTest` (login admin seedé, RBAC 401/403/201,
  rotation refresh, révocation logout, /me). **À vérifier par firas** : `mvn verify` (Docker requis
  pour Testcontainers) doit être vert ; login admin → token → appel protégé.
- **Semaine 1 — Jour 4 (frontend Angular) : CODE LIVRÉ, vérif en attente.** Angular 18 standalone
  + Material (Azure/Blue), état en **signals** (ADR-0002). `core/` : `AuthService` (login/register/
  refresh/logout/me, user dérivé du JWT), `TokenStore` (localStorage), intercepteur JWT + **refresh
  silencieux sur 401**, `authGuard` + `roleGuard` hiérarchique. `features/auth` (login, register
  ADMIN), `dashboard`, `layout` (topbar + sidenav). Routing lazy protégé. **Proxy dev** `/api`→:8080
  (pas de CORS). **À vérifier par firas** : backend up, `ng serve --proxy-config proxy.conf.json`
  → login admin → dashboard ; menu Utilisateurs visible en ADMIN.
- **Semaine 1 — Jour 5 (FastAPI + CI) : CODE LIVRÉ, vérif en attente.** Service IA : pool
  **asyncpg** (`core/db.py`), lifespan FastAPI (connect/disconnect résilient — démarre même si
  la base est down), endpoints `/health` (liveness) et `/health/ready` (readiness `SELECT 1` →
  200/503). Job **CI frontend activé** (`npm ci` + `npm run build` = validation AOT de tout le J4).
  Les 3 jobs (ai-service, backend, frontend) sont désormais actifs. **À vérifier par firas** :
  commit J4+J5, push → les 3 jobs verts dans Actions = **Semaine 1 bouclée**.
- **Semaine 1 : BOUCLÉE ET VÉRIFIÉE** — 3 jobs CI verts, stack up (login front → backend →
  base, `/health/ready` = database up). Tout est sur `main` (GitHub).
- **Semaine 2 — Jour 1 (import structuré) : BOUCLÉ ET VÉRIFIÉ** (CI verte + import 10k sans OOM). Flyway
  `V2__imports_tickets.sql` (tables `imports` + `tickets`, contraintes CHECK/FK, index ;
  entité `Ticket` différée au J2). Module `imports/` : détection type (magic bytes+extension)
  et encodage (BOM+UTF-8), parseurs **streaming** CSV (OpenCSV), XLSX (excel-streaming-reader,
  **sans OOM**), JSON (Jackson), TXT ; `RowCollector` (apercu+erreurs bornés). Endpoint
  **`POST /api/imports`** (ADMIN, multipart) → ligne `imports` AWAITING_VALIDATION + apercu +
  rapport d'erreurs ligne à ligne (pas de persistance de tickets — c'est le J2). Multipart 50 Mo,
  handlers 415/400/413. Test `ImportIntegrationTest` (CSV, XLSX généré, CSV malformé, RBAC 403).
  Générateur `scripts/generate_sample_csv.py` → `samples/tickets_10k.csv` (gitignoré). **À vérifier
  par firas** : `mvn verify` vert ; upload du CSV 10k → totalRows=10000 sans OOM.
- **Semaine 2 — Jour 2 (mapping + insertion, BACKEND) : CODE LIVRÉ, vérif en attente.**
  Parseurs refactorés en **streaming** (`RowHandler`/`stream()`, `parse()` par défaut). Fichier
  **stocké à l'upload** (`ImportStorage`, dossier `app.imports.storage-dir`) pour re-lecture au
  confirm. Entité `Ticket` + enums + `TicketRepository` ; `column_mapping` (jsonb) mappé dans
  `ImportJob`. Endpoint **`POST /api/imports/{id}/confirm`** (ADMIN) : re-parse streaming du
  fichier, construit les tickets via le mapping, **insertion par lots de 500 avec dedup
  external_ref**, persiste mapping + statut DONE. Handlers 400 (mapping sans `subject`) / 409
  (re-confirm). Test `ConfirmImportIntegrationTest`. **À vérifier par firas** : `mvn verify` +
  upload CSV → confirm mapping → tickets en base.
- **Semaine 2 — Jour 2 (mapping, FRONTEND) : CODE LIVRÉ, vérif en attente.** Feature Angular
  `imports/` : `ImportsService` (upload multipart, confirm), écran `ImportComponent` (choix
  fichier → upload → **aperçu 50 lignes + rapport d'erreurs**, mat-select par champ ticket avec
  **auto-mapping** heuristique des colonnes, bouton confirmer → snackbar inséré/ignoré). Route
  `/imports` (roleGuard ADMIN) + lien nav ADMIN. **À vérifier par firas** : `ng serve`, login
  admin → Imports → choisir CSV → mapping pré-rempli → Confirmer → tickets en base.
- **Semaine 2 — Jour 3 (chaîne asynchrone RabbitMQ) : BOUCLÉ ET VÉRIFIÉ** (chaîne Spring→MQ→FastAPI
  bout en bout : `ref=BOM2-*` reçu côté consommateur + idempotence `skipped:3` au ré-import ;
  consommateur résilient reconnecte après broker down). Mots de passe dev alignés sur `firas`.
  **Producteur Spring** : `spring-boot-starter-amqp`, `RabbitConfig` (exchange topic
  `supportiq.tickets`, queue `tickets.analyze` avec DLQ `tickets.analyze.dlq`, converter JSON),
  `TicketCreatedEvent` publié **après commit** (`@TransactionalEventListener(AFTER_COMMIT)` via
  `TicketsPersistedEvent`) — pas de message fantôme. Test unitaire `TicketEventPublisherTest`
  (mock RabbitTemplate). **Consommateur FastAPI** : `app/messaging/consumer.py` (aio-pika),
  topologie déclarée à l'identique, `queue.consume` → log/stub triage (S3), `message.process`
  (ack ; échec → DLQ), retries exponentiels, idempotence par external_ref (set mémoire au J3),
  démarré dans le lifespan (résilient si broker down). **VÉRIFIÉ 3a** : 5 messages dans
  `tickets.analyze` (RabbitMQ UI). **À vérifier 3b par firas** : restart ai-service → logs
  « Ticket recu … » + queue redescend à 0.
- **Semaine 2 — Jour 4 (webhook temps réel + liste tickets) : CODE LIVRÉ, vérif en attente.**
  **Webhook `POST /api/webhooks/tickets`** (hors JWT, `permitAll`) : auth par **clé API `X-Api-Key`
  + signature HMAC-SHA256 du corps brut `X-Signature`** (corps reçu en `byte[]` pour signer les octets
  exacts ; comparaison temps constant `MessageDigest.isEqual`), **rate limiting Bucket4j** par clé API
  (`bucket4j_jdk17-core` 8.19, interceptor → 429). Crée un ticket `source=WEBHOOK`, **idempotent par
  external_ref** (200 DUPLICATE), publie `ticket.created` **après commit** (réutilise la chaîne J3) → 202.
  Exceptions mappées ProblemDetail : 401 (auth), 400 (payload), 429 (quota), 409 (course unique-ref).
  **Liste `GET /api/tickets`** : pagination/tri/filtres **serveur** (JPA Specifications : `q`, `status`,
  `source`, `language` ; tri **whitelisté** ; `PageResponse` stable). Filtres category/priority/sentiment
  différés S3 (table `analyses` absente). **Frontend** : `features/tickets` (mat-table + mat-paginator
  serveur + matSort + filtres debounce en signals), `TicketsService`, route `/tickets` (tous rôles
  authentifiés) + lien nav. Tests : `WebhookSignatureVerifierTest`, `WebhookRateLimitInterceptorTest`
  (unitaires), `WebhookIntegrationTest` + `TicketListIntegrationTest` (Testcontainers). **À vérifier par
  firas** : `mvn verify` vert ; curl signé HMAC → 202 + ticket WEBHOOK ; `ng serve` → écran Tickets
  paginé/filtrable.
- **Semaine 2 — Jour 4 : VÉRIFIÉ** (webhook signé → 202 `ACCEPTED` + ticket WEBHOOK ; corps altéré
  → 401 ; mauvaise clé → 401 ; ré-envoi → 200 `DUPLICATE` ; `Ticket recu ref=WH-DEMO-1` côté FastAPI ;
  écran Tickets paginé/filtrable OK). Reste : `mvn verify` en CI + commit `s2-j4-webhook-tickets`.
- **Semaine 2 — Jour 5 (dataset synthétique) : BOUCLÉ ET VÉRIFIÉ** (génération lancée : test 300
  équilibré 60/cat + 150 FR/150 EN, train 774 ; juge 70b, filtre d'accord 30% gardés / 1004 générés ;
  échantillons relus, labels cohérents, aucune fuite). Multi-comptes Groq empilés (GROQ_API_KEY_2…),
  génération 8b + juge 70b, JSON tolérant (raw_decode), reprise sûre. `test.jsonl` committé.
  Générateur `eval/generate_dataset.py` : passerelle LLM (même chaîne de repli que le service),
  **génération conditionnée** (label = consigne, fiable par construction, jamais nommé dans le texte)
  + **filtre d'accord** sur le test set (2e appel LLM reclasse à l'aveugle, on garde si concordance
  `category`+`sentiment` ; backfill par cellule ; accord priorité reporté non bloquant). Équilibrage
  catégorie×langue, dedup, **test construit en premier → étanchéité train→test**. Sortie JSONL
  (`id,language,subject,body,text,category,priority,sentiment,style,split,source`). Défauts 800 train
  + 300 test. `eval/requirements.txt` (litellm/dotenv/pydantic), `eval/README.md` (méthodologie +
  honnêteté synthétique), `.gitignore` : **`test.jsonl` versionné**, `train.jsonl` ignoré. Méthodo
  documentée dans README (pas d'ADR : 0003 réservé fine-tuning vs baseline). **À faire par firas** :
  `pip install -r eval/requirements.txt` puis `python eval/generate_dataset.py` → vérifier l'équilibre
  affiché + relire un échantillon de `test.jsonl`, puis commit `test.jsonl`.
- **Semaine 3 — Jour 1 (détection langue + baselines) : CODE LIVRÉ, exécuté et vérifié (ML) en sandbox.**
  `ai-service/app/nlp/language.py` : détection FR/EN par **heuristique stopwords + diacritiques**
  (zéro dépendance) → **98,0 %** sur le test gelé. `eval/baselines.py` (scikit-learn) : 3 baselines
  (**Majorité**, **TF-IDF+LinearSVC**, **LLM zero-shot**) sur les 3 têtes, P/R/F1 par classe + matrice
  de confusion + exemples d'erreurs → rapport commité `eval/results/baseline_s3j1.md`. **Résultats
  TF-IDF (macro-F1)** : category **0,91** (vs majorité 0,07 — très séparable lexicalement),
  priority **0,40** (vs 0,17 — quasi non apprenable du texte seul, confusion ≈ bruit), sentiment
  **0,45** (POS classe faible, 37 support). `eval/requirements-ml.txt` (scikit-learn ; wheels, pas de
  Rust). **Baseline LLM 0-shot lancée par firas** : category **0,86** (< TF-IDF 0,91), priority **0,36**,
  sentiment **0,62** (> TF-IDF 0,45). Croisement clé : TF-IDF gagne le lexical (catégorie), le LLM gagne
  le nuancé (sentiment), priorité échoue partout — justifie empiriquement l'architecture hybride. Rapport
  commité.
- **Semaine 3 — Jour 2 (fine-tuning XLM-R) : EXÉCUTÉ ET VÉRIFIÉ par firas (Kaggle GPU).**
  `ml/finetune_xlmr.ipynb` (compatible Kaggle/Colab) : encodeur **xlm-roberta-base partagé + 3 têtes**,
  boucle PyTorch (somme 3 CE, 5 epochs, lr 2e-5), éval sur test gelé, **export ONNX `dynamo=False`**
  (parité onnxruntime OK). **v2 (passe propre : mean-pooling, split validation, perte sentiment pondérée,
  best-checkpoint sur val)**. **Résultats macro-F1 v2 vs TF-IDF** : catégorie **0,95** (+0,04, mean-pool),
  priorité **0,33** (~bruit, non apprenable → règles), sentiment **0,60** (+0,15 vs TF-IDF, = LLM 0,62 sans
  coût ; rappel NEG **0,43→0,66**). Val a révélé un sur-apprentissage après epoch 5 → best-checkpoint l'a
  neutralisé. **ADR-0003 rédigé** (`docs/adr/0003-fine-tuning-vs-baseline.md`) : local pour catégorie+
  sentiment, **priorité par règles**, escalade LLM sur confiance faible. Écarts Kaggle : données sous
  `/kaggle/input`, Internet ON requis (téléchargement HF), download via `/kaggle/working`. **À faire par firas** : dézipper `triage_model.zip` dans `ml/artifacts/`, commit.
- **Semaine 3 — Jour 3 (pipeline hybride + routeur de confiance) : BOUCLÉ ET VÉRIFIÉ** (4 tickets webhook
  analysés + persistés en `analyses` : 10019 DEMANDE/POS **local seul** conf 0,87, 10017/10018 **hybride
  tête-par-tête** (catégorie locale sûre + sentiment escaladé), 10016 ambigu **escalade totale** ;
  catégories toutes correctes ; taux d'escalade 3/4 → seuil à calibrer en ADR-0004/S3-J5).
  `app/pipeline/` : `local_model.py` (ONNX + tokenizer XLM-R, chargement paresseux/résilient →
  `classify()` renvoie label+confiance softmax, ou None si artefact absent → escalade), `rules.py`
  (**priorité par règles** ADR-0003 : mots-clés urgence + catégorie×sentiment), `llm_classifier.py`
  (few-shot JSON via `core.llm`, validation enums + retry, fallback None ; mitigation prompt injection),
  `triage.py` (langue → local → **routeur seuil 0.80** → escalade LLM si tête peu sûre → règles →
  `AnalysisResult` avec `model_used`/`escalated_to_llm`), `store.py` (insert `analyses` asyncpg, résilient).
  Consumer câblé : `_analyze` lance le pipeline + persiste + logue la décision. Migration **`V3__analyses.sql`**
  (backend). `config.model_dir=/models`, requirements (onnxruntime, sentencepiece), docker-compose monte
  `./ml/artifacts:/models:ro`. Tests `test_rules.py` (5/5) + `test_triage_router.py` (monkeypatch local/LLM).
  **À vérifier par firas** : modèle dézippé dans `ml/artifacts/`, `docker compose up` (backend applique V3),
  import CSV → logs `Ticket … analysé: cat=… modèle=xlm-r-onnx/hybrid escalade=…` + lignes en table `analyses`.
- **Semaine 3 — Jour 4 (similarité + mots-clés) : BOUCLÉ ET VÉRIFIÉ** (`/similar` sur ticket 10020 →
  paraphrase 10021 en tête à **0.9806** avec **`is_duplicate=true`** ; corpus 10019 tickets embeddés ;
  fix recall HNSW via `SET` session + sous-requête native). Détail ci-dessous.
  Migration **`V4__embeddings.sql`** (`CREATE EXTENSION vector`, table `embeddings(ticket_id PK,
  vector(768), model)`, index **HNSW** cosine). `app/pipeline/embeddings.py` : embed **multilingual-e5-base**
  (sentence-transformers, préfixe `query:`, normalisé), `store_embedding` (upsert `::vector`), `find_similar`
  (KNN `<=>` + **règle de doublon** cosinus ≥ 0.92 même catégorie → `is_duplicate`), `backfill`.
  `app/pipeline/keywords.py` : **KeyBERT** réutilisant l'embedder e5 (lazy/résilient → `[]`). Endpoints
  **`POST /similar`** (ticket_id|text, k) et **`POST /embeddings/backfill`** ; schemas `SimilarRequest/
  SimilarTicket`. `triage.analyze` remplit désormais `keywords` ; consumer stocke l'embedding après analyse.
  `config` : `embedding_model`, `duplicate_threshold=0.92`. requirements `keybert` ; docker-compose : volume
  `hf-cache` (évite re-téléchargement e5 ~1 Go). Test `test_embeddings_format`. **À vérifier par firas** :
  `docker compose up -d --build ai-service` (backend applique V4), 1er ticket → téléchargement e5, puis
  `POST /embeddings/backfill` → `POST /similar {ticket_id}` renvoie voisins + `is_duplicate`.
- **Semaine 3 — Jour 5 (harness d'éval + observabilité) : EXÉCUTÉ ET VÉRIFIÉ.** Harness lancé (300
  tickets) : **catégorie local 0.95 / LLM 0.87 ; sentiment local 0.60 / LLM 0.70**. Balayage seuil :
  monter le seuil double l'escalade (46%→100%) pour +0.03 sentiment et **dégrade** la catégorie →
  **seuil figé à 0.50** (`config.confidence_threshold=0.50`, ADR-0004 **accepté**). Correctif majeur :
  passerelle LLM `core/llm.py` refaite en **multi-clés Groq + 8b-instant** (le `GROQ_API_KEY` en virgules
  cassait litellm → 401 ; 8b = budget large). Caveat : ~80 appels LLM ont manqué de budget en fin de run
  (chiffres LLM/hybride légèrement sous-estimés, conclusion robuste). **Reste pour firas** : Démo 3
  (import CSV → analyses), mettre `CONFIDENCE_THRESHOLD=0.50` dans `.env`, commit + push (CI eval).
  `eval/evaluate_pipeline.py` (lancé **dans le conteneur** via `docker compose exec ai-service`) : sur le
  test gelé, calcule **local seul / LLM seul** une fois, puis **balaye le seuil** hybride (escalade vs
  macro-F1, calcul stdlib sans sklearn) → rapport `eval/results/pipeline_eval_s3j5.md`. Mount `./eval:/eval`.
  **Langfuse** branché sur `core/llm.py` (callbacks litellm, activés seulement si clés présentes ; config +
  requirements `langfuse` + service self-host commenté dans compose). **CI** : job `eval` activé →
  `eval/check_dataset.py` (garde-fou intégrité du test gelé, stdlib ; F1-regression complète reportée faute
  de registre de modèles). **ADR-0004** rédigé (seuil : critère = plus bas seuil gardant F1 à ~2 pts du max).
  **À faire par firas** : `docker compose up -d ai-service` (mount eval), `docker compose exec ai-service
  python /eval/evaluate_pipeline.py` → remplir le tableau ADR-0004 + figer `confidence_threshold` ; Démo 3.
- **Semaine 3 : BOUCLÉE** (harness lancé, seuil 0.50 figé, CI verte). Bonus post-S3 : **backend
  dockerisé** (`backend/Dockerfile` + service compose) → tout le stack lit le même `.env` (JWT/admin/
  webhook vérifiés : login `firas@gmail.com` OK, webhook re-cléfé `supportiq-webhook-key` → ACCEPTED).
- **Semaine 4 — Jour 1 (API dashboard) : BOUCLÉ ET VÉRIFIÉ** (V5 appliquée `success=t` ; KPIs sur 10 022
  tickets : 7 analysés, escalade 71 % *héritée du seuil 0.80* — baissera vers ~46 % avec le seuil 0.50 ;
  **latence 18 ms au 1er appel puis ~5 ms** grâce au cache → objectif < 100 ms largement tenu).
  Migration **`V5__dashboard_views.sql`**
  : vues **`v_ticket_stats`** (KPIs), **`v_category_trends`** (volume/jour×catégorie), **`v_hourly_load`**
  (heatmap horaire) + index `created_at`. Module `dashboard/` : `DashboardRepository` (**JdbcTemplate** sur
  les vues — agrégats read-only, pas d'entité JPA), DTOs `KpiResponse` (volumes + taux haute priorité /
  négatif / **escalade LLM** + confiance moyenne) et `TrendsResponse` (daily/byCategory/bySentiment/
  byPriority/hourly en **un seul appel**), `DashboardService` (**@Cacheable**, fenêtre `days` bornée 365,
  champs SQL **whitelistés** anti-injection). **Cache Caffeine 60 s** (`common/CacheConfig`, TTL+maxSize).
  Endpoints **`GET /api/dashboard/kpis|trends|alerts`** réservés **MANAGER+** (`@PreAuthorize`) ; `alerts`
  renvoie `[]` (table `alerts` + détecteurs en S7, contrat exposé d'avance pour le J2). Test
  `DashboardIntegrationTest` (KPIs calculés, séries, RBAC AGENT 403 / sans jeton 401). **À vérifier par
  firas** : `docker compose up -d --build backend` (Flyway V5), curl kpis/trends en MANAGER, latence < 100 ms.
- **Semaine 4 — Jour 2 (dashboard Angular) : CODE LIVRÉ, vérif en attente.** `chart.js ^4.5.1` ajouté
  (**pas ng2-charts** : exige Angular 21). `shared/chart/chart.component.ts` : wrapper standalone ~40 lignes
  (canvas + `@Input config`, `update()` sur changement, `destroy()` à la destruction — pas de fuite mémoire).
  `core/dashboard/dashboard.service.ts` + `core/models/dashboard.models.ts` (miroirs des DTOs).
  Écran `features/dashboard` : **5 cartes KPI** (total, analysés+confiance, %haute priorité, %négatif,
  **%escalade LLM = métrique de coût**), **5 graphiques** (courbe évolution/catégorie, doughnuts catégorie
  et sentiment, barres horizontales priorité, barres heatmap horaire à opacité proportionnelle), **filtre
  période 7/30/90 j**, états chargement/erreur, configs Chart.js en `computed()` (recalcul auto), palette
  stable par label. **RBAC** : route `/dashboard` protégée `roleGuard('MANAGER')`, lien nav masqué pour
  AGENT, et **repli du roleGuard changé `/dashboard` → `/tickets`** (sinon boucle infinie pour un AGENT).
  **À vérifier par firas** : `npm install` (chart.js), `ng serve --proxy-config proxy.conf.json`, login
  admin → dashboard avec KPIs + graphiques ; tester un compte AGENT → redirigé vers /tickets.
- **Semaine 4 — Jour 3 (recherche full-text) : BOUCLÉ ET VÉRIFIÉ** (V6 appliquée `success=t` ;
  `EXPLAIN ANALYZE` → **`Bitmap Index Scan on ix_tickets_search_vector`**, **Execution Time 0,217 ms**
  sur 10 022 tickets → objectif < 200 ms très largement tenu ; stemming FR confirmé par le plan
  (`paiement`/`paiements` → lexème `'pai'`) ; `remboursement` → 2247 résultats ; recherche+filtre
  combinés OK). Migration
  **`V6__fulltext_search.sql`** : colonne **`search_vector` GENERATED ALWAYS STORED** (`CASE language
  'en'→to_tsvector('english',…) SINON 'french'`, **forme 2 args obligatoire** car une colonne générée
  exige une expression IMMUTABLE — la forme 1 arg dépend de `default_text_search_config`, donc STABLE),
  **index GIN** dessus + index **trigram** (`pg_trgm`) sur `subject` pour le flou. Backend :
  `TicketSearchRepository` (SQL natif : **`websearch_to_tsquery`** tolérant à la saisie libre, filtres
  status/source/language **+ category/priority/sentiment** via `analyses`, tri **par pertinence
  `ts_rank`** quand `q` présent sinon colonne whitelistée, LIMIT/OFFSET + COUNT), `TicketSearchCriteria`,
  `TicketQueryService` réécrit (validation/normalisation enums + valeurs d'analyse whitelistées),
  `TicketController` étendu. `TicketSpecifications` **vidée** (obsolète : Criteria ne sait pas exprimer
  tsvector/ts_rank). Frontend : filtres catégorie/priorité/sentiment ajoutés, **chips de filtres actifs
  retirables** + « Tout effacer », indicateur **« triés par pertinence »**, compteur de résultats.
  Test `TicketSearchIntegrationTest` (stemming FR `paiements`→`paiement`, EN `refunds`→`refund`, corps
  et pas que sujet, recherche+filtre combinés, filtres d'analyse, 400 si valeur invalide).
  **À vérifier par firas** : `docker compose up -d --build backend` (Flyway V6), recherche curl + UI,
  `EXPLAIN ANALYZE` pour confirmer l'usage du GIN et la latence < 200 ms.
- **Semaine 4 — Jour 4 (fiche ticket + human-in-the-loop) : BOUCLÉ ET VÉRIFIÉ** (fiche 10020 complète :
  analyse HIGH/FACTURATION/NEG + 5 mots-clés + `similar` avec 10021 à **0.9806 `duplicate=true`** ;
  correction `category` → `annotations` : `predicted=FACTURATION, corrected=RECLAMATION, corrected_by=3`
  et analyse mise à jour). **Correctifs de vérif** : (a) `confidence` est un `NUMERIC` → lire en
  **`getBigDecimal`** (le cast `(Double)` levait une ClassCastException → 500) ; (b) `SimilarTicketClient`
  réécrit en **`RestTemplate` + `HttpEntity`** avec corps JSON littéral — `RestClient` + `Map` partait avec
  un corps vide (FastAPI 422 « Field required »).
  Migration **`V7__annotations.sql`** (`annotations(ticket_id, field[priority|category|sentiment],
  predicted, corrected, corrected_by, created_at)` + FK + CHECK + index ; **historique conservé**, pas
  d'UPDATE en place → futur export JSONL de ré-entraînement S8). Backend : `TicketDetailResponse`
  (ticket + `Analysis` + `SimilarTicket[]`), `TicketDetailRepository` (JdbcTemplate, jointure
  ticket+analyse ; insertAnnotation/applyCorrection/merge), **`SimilarTicketClient`** (RestClient vers
  **FastAPI `/similar`** — respecte la frontière §6 : le calcul vectoriel reste au plan de calcul ;
  **résilient** → liste vide si l'IA est down), `TicketDetailService` (champs+valeurs **whitelistés**,
  ordre trace-puis-applique pour capturer `predicted`). Endpoints : **`GET /api/tickets/{id}`**,
  **`POST /{id}/annotations`**, **`POST /{id}/merge`** ; `TicketStateException` → **409** (déjà fusionné,
  auto-fusion, ticket non analysé). Config `app.ai-service.base-url` + `AI_SERVICE_URL` dans compose.
  Frontend : `ticket-detail.component` (en-tête + corps, carte analyse avec **badge de confiance
  colorié** high/medium/low, mots-clés en chips, **dropdowns de correction** → snackbar, liste des
  similaires avec `doublon probable` → bouton **Fusionner**), route `/tickets/:id`, **lignes de la table
  cliquables**. Test `TicketDetailIntegrationTest` (détail+analyse, 404, correction tracée *et* appliquée,
  400 valeur invalide, 409 non analysé / auto-fusion / double fusion, similaires vides si IA injoignable).
  **À vérifier par firas** : `docker compose up -d --build backend` (Flyway V7), ouvrir un ticket depuis
  la liste, corriger une catégorie → vérifier la ligne dans `annotations`, tester la fusion d'un doublon.
- **Semaine 4 — Jour 5 (temps réel STOMP + jalon mi-parcours) : CODE LIVRÉ, vérif en attente.**
  Backend : dép `spring-boot-starter-websocket`, `realtime/WebSocketConfig` (endpoint **`/ws`**, broker
  simple `/topic`, origines 4200 autorisées), `RealtimeEvent` (signal minimal : type + ids + labels),
  `RealtimeBroadcaster` (best-effort, n'échoue jamais l'opération métier), diffusion depuis
  `TicketEventPublisher` **après commit**, `/ws/**` en `permitAll` (les messages ne sont que des signaux ;
  les données restent derrière l'API protégée). **Boucle asynchrone fermée (§3)** : ai-service publie
  **`ticket.analyzed`** après analyse (exchange mémorisé au démarrage, publication best-effort) → queue
  `tickets.analyzed` + `TicketAnalyzedListener` (`@RabbitListener`) → rediffusion WebSocket. Frontend :
  `@stomp/stompjs ^7.3.0` (WebSocket natif, pas de SockJS), `core/realtime/realtime.service.ts`
  (connexion unique dans le layout, reconnexion auto 5 s, signals `connected`/`newTickets`/`newAnalyses`),
  **badge « live »** dans la topbar, **bandeau « N nouveaux tickets » + bouton Rafraîchir** sur la liste,
  compteur remis à zéro après rechargement. **`docs/revue-mi-parcours.md`** : tableau de couverture
  F1-F5/F9-F12 + chiffres à citer + points d'honnêteté (support de la revue avec l'encadrant).
  **À vérifier par firas** : `npm install` (stompjs), `docker compose up -d --build backend ai-service`,
  `ng serve` → badge live vert, envoyer un webhook → bandeau apparaît, puis analyse poussée.
- **Refonte d'interface (hors planning, demandée par firas) : CODE LIVRÉ, vérif en attente.**
  Passe design complète sur le frontend, **sans aucune modification du backend ni des contrats d'API**.
  **Système de design** : `src/styles/_tokens.scss` (couleur, typo, espacement, rayon, ombre, motion —
  en custom properties CSS pour permettre la bascule à l'exécution), `_base.scss` (reset, typo,
  a11y, animations), `_components.scss` (primitives : card/btn/badge/input/segmented/data-table/
  banner/empty-state/skeleton/meter/kbd/pill), `_material.scss` (Angular Material **gardé** pour le
  comportement — select, menu, dialog, snack-bar, tooltip — et **remplacé** partout où il n'était que
  présentation ; ripple désactivé). Police **Inter** + **Material Symbols Rounded**, thème **clair/sombre**
  (`data-theme` sur `<html>`, script anti-FOUC inline dans `index.html`, `ThemeService` qui suit
  `prefers-color-scheme` tant que l'utilisateur n'a pas choisi).
  **Composants partagés** (`shared/ui/`) : icon, badge (traduit les enums via `shared/labels.ts`),
  stat-card, sparkline SVG maison, count-up, empty-state, skeleton, page-header, confirm-dialog,
  **palette de commandes Ctrl/⌘+K** (navigation, ouverture d'un ticket par numéro, bascule de thème,
  filtrage par sous-séquence, commandes filtrées par rôle) ; `RelativeTimePipe`/`AbsoluteTimePipe` ;
  `ToastService` (wrapper MatSnackBar, position + durée indexée sur la gravité).
  **Vocabulaire produit** (`shared/labels.ts`) : `NEG`→« Mécontent », `WEBHOOK`→« Temps réel »,
  escalade LLM→« Analyse approfondie », confiance→« Fiabilité ». **F11 reste démontrable** : le taux
  et la fiabilité sont toujours affichés (carte « Qualité de l'analyse »), c'est le jargon qui disparaît.
  **Écrans refondus** : shell (sidebar en 2 sections Travail/Administration + rail repliable mémorisé,
  topbar translucide, avatar + menu), login (split-screen + panneau de marque, redirection selon le
  rôle), liste tickets (onglets de statut, panneau de filtres en pastilles, chips actifs, tri whitelisté,
  squelettes, état vide, **filtres écrits dans l'URL** → recherche partageable), fiche ticket (2 colonnes,
  anneau de fiabilité, **correction en 1 clic**, fusion confirmée), dashboard (KPI réordonnés par valeur
  d'action, barres classées, graphiques **thème-aware**), imports (3 étapes + glisser-déposer), équipe
  (cartes de rôle explicites + jauge de force), **page 404**.
  **Vérifié statiquement** : `tsc --noEmit` vert, **`ngc --noEmit` vert avec `strictTemplates`** (AOT),
  toutes les feuilles SCSS compilées. Budgets `angular.json` relevés (anyComponentStyle 10/20 ko).
  **Doc** : `docs/design-system.md`. **À vérifier par firas** : `ng serve --proxy-config proxy.conf.json`.
  **Complément demandé par firas (seule modification backend de la refonte)** : `TicketSummaryResponse`
  reçoit 3 champs (`priority`, `category`, `sentiment`) et `TicketSearchRepository` 3 colonnes au SELECT
  (`a.priority, a.category, a.sentiment`) — la jointure `analyses` existait déjà pour les filtres depuis
  S4-J3, donc **aucune requête supplémentaire**. Champs `null` si le ticket n'est pas encore analysé
  (jointure externe) : la liste affiche « en attente ». Factory morte `TicketSummaryResponse.from(Ticket)`
  supprimée (aucun appelant). 2 tests ajoutés à `TicketSearchIntegrationTest`
  (`summary_carriesAnalysisFields`, `summary_analysisFieldsAreNullWhenNotAnalysed`).
  **À vérifier par firas** : `mvn verify` vert puis `docker compose up -d --build backend`.
- **Passe 2 d'interface — bilingue + finition (hors planning, demandée par firas) : CODE LIVRÉ,
  vérif en attente.** **Backend non touché.**
  **i18n FR/EN maison** (`core/i18n/`) : `translations.fr.ts` = source de vérité (329 clés) dont on
  dérive `TranslationKey` puis `Dictionary`, que `translations.en.ts` **doit** satisfaire → une clé
  manquante ou mal orthographiée ne compile pas. `I18nService` en signals (langue mémorisée, repli sur
  `navigator.language`, `lang` de `<html>` synchronisé, `locale()` pour nombres/dates), pipe `t`
  **impur** (un pipe pur est mémoïsé sur ses arguments : la clé ne changeant pas, l'UI resterait figée).
  Bascule **instantanée sans rechargement**. `@angular/localize` écarté (un bundle par langue +
  rechargement), ngx-translate/transloco écartés (dépendance + perte du typage). Sélecteur de langue
  dans la topbar **et** sur l'écran de connexion, + commande dans la palette Ctrl/⌘+K.
  **Tous les écrans traduits** : shell, login, tickets, fiche, dashboard, imports, équipe, 404, palette,
  toasts, dialogues, en-têtes de table, légendes de graphiques, états vides, messages de validation.
  `labels.ts` ne contient plus **aucune** chaîne : uniquement des clés + la sémantique (ton, icône).
  **Colonne Priorité nettoyée** : suppression des glyphes `priority_high` (« ! »), `drag_handle` (« ≡ »)
  et `expand_more` (« ⌄ ») qui se lisaient comme de la ponctuation → pastille de couleur pleine + libellé.
  **Illustrations SVG maison** (`shared/ui/illustration.component.ts`, 6 scènes) construites sur les
  tokens → thème-aware, ~300 o chacune, aucune image externe. Utilisées dans les états vides, le 404 et
  la zone de dépôt.
  **Dashboard enrichi** : actions rapides (raccourcis vers une file déjà filtrée via query params),
  section « À retenir » (lecture automatique : tendance, 1er motif, seuils 25 %/30 %, heure de pointe),
  fil « Derniers tickets » (réutilise `GET /api/tickets?size=5` — **aucun nouvel appel d'API**).
  **Vérifié** : `tsc` vert, `ngc --noEmit` vert avec `strictTemplates`, Sass compilé, parité des
  dictionnaires 329/329, balayage automatique confirmant **0 chaîne en dur** dans les gabarits.
  **À vérifier par firas** : `ng serve` → bascule FR/EN instantanée sur tous les écrans.
  **Logo Proxym intégré** : fichier déplacé de la racine vers `frontend/public/brand/proxym-logo.png`
  (le dossier `public/` est déjà servi à la racine par `angular.json`, aucun réglage à ajouter).
  Composant `shared/ui/brand.component.ts` créé : le bloc de marque était recopié en SVG à **3 endroits**
  (sidebar, panneau de connexion, en-tête mobile) — désormais une seule source. Utilisé aussi comme
  **favicon PNG** (le `.ico` reste en repli), **apple-touch-icon** et sur l'**écran d'amorçage**.
  Sur le panneau de connexion, le logo est posé sur une **tuile blanche translucide** : un logo
  arc-en-ciel sur le dégradé indigo devenait illisible. `width`/`height` explicites partout (sinon
  la sidebar sursaute au chargement de l'image). L'écusson dégradé maison est supprimé.
  **Favicon** : `public/favicon.ico` régénéré depuis le logo (Pillow) — le logo est **recadré sur son
  contenu** (625×620 → 542×542 utiles) puis recentré avec ~6 % de marge, sinon l'icône paraît décalée
  et surdimensionnée dans la barre d'onglets. Le `.ico` embarque **5 résolutions** (16/24/32/48/64,
  12 ko) : sans cela Windows redimensionne à la volée et l'icône est floue. Déclinaisons PNG générées
  (`favicon-32`, `favicon-192`, `apple-touch-icon` 180) et déclarées **après** le `.ico` (le navigateur
  retient la dernière déclaration qu'il comprend). Le `.ico` reste requis : les navigateurs demandent
  `/favicon.ico` même sans balise, et les raccourcis Windows ne lisent que ce format. Le composant de
  marque et l'écran d'amorçage servent la version **192 px** (12 ko) et non le source 625 px (30 ko) —
  fichier déjà téléchargé comme favicon, donc zéro requête supplémentaire.
- **Semaine 5 — Jour 1 (base de connaissances + ingestion documentaire) : CODE LIVRÉ, vérif en attente.**
  Migration **`V8__kb_documents.sql`** (title, source, chunk_index, heading, content, `vector(768)`,
  model, updated_at ; index **HNSW** cosinus + index sur `source` ; `UNIQUE(source, chunk_index)` pour
  un ré-import idempotent). Écarts §4 assumés : ajout de `heading` (chemin des titres — support des
  **citations** en S5-J3/J4) et `model` (traçabilité d'un ré-index), cohérent avec `embeddings`.
  **Service IA `app/kb/`** : `loader.py` (Markdown/TXT/PDF via **PyMuPDF** `sort=True` — ordre de
  lecture visuel, là où pypdf entrelace les colonnes ; décodage tolérant UTF-8→cp1252→latin-1 ;
  recollage des césures PDF `rembour-\nsement`), `chunker.py` (**découpage sémantique** : titres
  Markdown → paragraphes → phrases, budget 900 car., **recouvrement** 140 car., plancher 220 car.,
  fusion intra-section uniquement — jamais deux sujets dans un fragment ; `heading` = chemin
  « Facturation > Remboursement », **inclus dans le texte embeddé**), `store.py` (asyncpg,
  remplacement **transactionnel** DELETE+INSERT et non UPSERT — un doc réécrit peut avoir moins de
  fragments, les surnuméraires resteraient indexés), `service.py` (ingest / documents / remove /
  reindex / search). **Correctif e5** : `embed()` prend un `prefix` — e5 est **asymétrique**
  (`passage:` à l'indexation, `query:` à l'interrogation) ; `_to_pgvector` → `to_pgvector` (public,
  partagé tickets/KB). Endpoints FastAPI `/kb/documents` (POST multipart, GET), `/kb/documents/{source}`
  (DELETE), `/kb/reindex?force=`, `/kb/search`. requirements : `pymupdf`, `python-multipart`.
  **Backend `knowledge/`** : `KbController` (**ADMIN** sur écriture, **AGENT+** sur `/search`),
  `KbService` (garde-fous d'entrée : extension, taille 10 Mo, **neutralisation du nom de fichier**
  contre la traversée de chemin), `KbRepository` (JdbcTemplate — lister est une agrégation SQL, la
  faire transiter par HTTP casserait la page d'admin dès que l'IA redémarre), `KbClient`
  (RestTemplate ; **contrairement à `SimilarTicketClient`, les échecs ne sont pas avalés** — un import
  raté en silence laisserait croire la FAQ indexée), `KbException` → ProblemDetail avec statut préservé
  (415 format ≠ 503 panne).
  **Frontend `features/knowledge/`** : dépôt glisser-déposer, état de la base (documents / fragments /
  **interrogeables** — l'écart déclenche la ré-indexation), liste avec suppression confirmée, et
  **banc d'essai de recherche** (le livrable « KB interrogeable »). Route `/knowledge` roleGuard ADMIN,
  entrée de nav, commande dans la palette, **40 clés i18n FR/EN** (369/369 à parité).
  **Corpus de démo** `ai-service/fixtures/kb/` : 4 FAQ Markdown fictives mais réalistes (facturation,
  compte, livraison/technique, orders EN) **alignées sur les catégories du triage** → 20 fragments,
  373 car. de moyenne. Monté en `/fixtures:ro` dans compose.
  **Vérifié** : 20 tests Python verts (7 chunker + 6 loader + rules + format), `ruff` vert, `ngc
  --noEmit` avec `strictTemplates` vert, SCSS compilé, arité/accolades Java contrôlées.
  **VÉRIFIÉ par firas** : 4 FAQ indexées (20 fragments, tous « Prêt »), « comment obtenir un
  remboursement ? » → *Facturation > Demander un remboursement* **en tête à 87 %**, suivi des autres
  sections Facturation (82/82/81 %). Retrieval fonctionnel de bout en bout.
  **2 correctifs post-vérif** : (a) **balisage Markdown non nettoyé** — les `**14 jours**` étaient
  stockés, donc *embeddés* (tokens parasites dans le vecteur), *affichés* tels quels, et seraient
  *injectés dans le prompt* en S5-J3 ; `strip_markup()` retire gras/italique/code/liens/citations
  **après** extraction des titres (le `#` porte la structure, pas la mise en forme), en préservant
  `order_id` et `2*3` ; 3 tests ajoutés. **Ré-import des documents nécessaire** (la ré-indexation ne
  recalcule que les vecteurs, pas le contenu stocké). (b) **compression des scores** : les cosinus
  bi-encodeur tiennent dans une bande étroite (0,75-0,92), les barres paraissaient toutes pleines →
  `relevanceBar()` étale la barre sur la plage réellement utilisée, le **chiffre reste la valeur
  vraie**. C'est aussi l'argument chiffré en faveur du reranking cross-encodeur du J2 : un
  bi-encodeur classe bien mais discrimine mal.
  **Correctif frontend** : le banc d'essai utilisait `(ngSubmit)` sur un `<form>` **sans directive**
  (ni `[formGroup]` ni `FormsModule`) → `NgForm` ne s'attachait pas, l'événement n'était jamais émis
  et le navigateur rechargeait la page. Passé en `(submit)` + `preventDefault()`. **Leçon** :
  `strictTemplates` valide les *entrées* mais **pas les noms d'événements** — une sortie inexistante
  devient un écouteur DOM silencieux.
- **Semaine 5 — Jour 2 (retrieval hybride + éval recall@k) : CODE LIVRÉ, exécution en attente.**
  `app/kb/lexical.py` : **BM25** (`rank_bm25`), index **en mémoire** reconstruit depuis
  `kb_documents` et **invalidé à chaque écriture** (ingest/delete/reindex) ; tokenisation FR+EN sans
  accents (« delai » trouve « délai » — ce que la colonne générée de S4-J3 ne pouvait pas faire, une
  expression IMMUTABLE étant exigée), liste de stopwords **courte** volontairement (l'IDF pénalise
  déjà les termes fréquents ; une liste longue supprimerait « pas »/« no » qui portent la négation),
  score nul écarté. `app/kb/retrieval.py` : **fusion RRF** `1/(k+rang)`, k=60 — choisie *contre* une
  moyenne pondérée parce que cosinus (borné, comprimé 0,75-0,92) et BM25 (non borné, dépendant du
  corpus) ne vivent pas sur la même échelle ; RRF **jette les scores et ne garde que le rang**, donc
  aucun α à recalibrer. Rappel élargi (`pool_factor=4`) avant fusion. `app/kb/rerank.py` :
  **cross-encodeur** `mmarco-mMiniLMv2-L12-H384-v1` (multilingue, ~470 Mo) sur les seuls candidats
  fusionnés — bi-encodeur = rappel large et bon marché, cross-encodeur = précision chère et courte ;
  logit → sigmoïde (forme stable pour les logits très négatifs) pour rester dans [0,1].
  **Correctif e5 hérité du J1** : `embed(prefix=)` déjà en place, `store.all_chunks(with_meta=True)`
  ajouté pour BM25. Config : `rrf_k`, `retrieval_pool_factor`, `rerank_enabled`, `rerank_model`.
  **API** : `KbSearchRequest.mode` (`hybrid` défaut / `vector`) traverse FastAPI **et** Spring
  (`safeMode()` normalise, une valeur inconnue retombe sur l'hybride — c'est une stratégie interne,
  pas une donnée métier). **Frontend** : comparateur segmenté Hybride/Vectorielle dans le banc
  d'essai, relance automatique au changement (+5 clés i18n, 374/374 à parité).
  **Éval** : `eval/datasets/kb_questions.jsonl` = **44 paires** annotées à la main couvrant les
  **20/20 sections** (8 en anglais) — annotées par **(source, heading)** et non par `id`, car les id
  changent à chaque ré-import (remplacement transactionnel). `eval/eval_retrieval.py` compare
  4 régimes (vectoriel seul / BM25 seul / RRF / RRF+reranking) sur recall@1/3/5 + **MRR**, réindexe
  le corpus au démarrage pour être reproductible, écrit `eval/results/retrieval_s5j2.md`.
  **Vérifié** : 33 tests Python verts (8 nouveaux sur tokenisation + RRF), `ruff check app tests`
  vert (la CI lint aussi `tests`), `ngc --noEmit` avec `strictTemplates` vert, SCSS compilé, arité
  Java contrôlée. `litellm` absent du sandbox → `test_health`/`test_triage_router` non exécutés ici
  (verts en CI).
  **EXÉCUTÉ ET VÉRIFIÉ par firas — RÉSULTAT NÉGATIF sur le reranking (ADR-0005 accepté).**
  Chiffres définitifs : vectoriel seul MRR **0,913** / recall@5 0,955 / 58 ms ; BM25 seul 0,883 /
  0,932 / 0,1 ms ; **RRF 0,900 / 0,955 / 58,6 ms** ; RRF+reranking 0,859 / 0,932 / **1 019 ms**.
  **ERREUR DE MESURE CORRIGÉE — à retenir** : la 1ʳᵉ exécution donnait 17 208 ms pour le reranking
  et j'ai annoncé « 170× ». Faux : le chiffre incluait le **téléchargement** du modèle (471 Mo à
  ~820 kB/s ≈ 9,6 min) amorti sur 44 questions. Surcoût réel : **~17×**. Le même défaut faisait
  sortir le vectoriel seul (103 ms) plus lent que la fusion qui l'englobe (73 ms) — résultat
  impossible qui aurait dû alerter. Correctif : **passe à blanc** avant chronométrage.
  **Analyse des désaccords ajoutée** (14 questions sur 44 divergent) — indispensable : 0,013 de MRR
  sur 44 questions = **1 question**, l'agrégat ne permet de rien conclure.
  **Duels** : vectoriel vs RRF = **3–3, égalité parfaite**. RRF rattrape un échec *total* du vecteur
  (« le site plante… ai-je été débité » : absent → rang 4) mais perd une question que le vecteur
  classait 1ʳᵉ (« je veux être remboursé… » : 1 → absent). **Mécanisme** : `pool_factor=4 × k=5 = 20`
  = **tout le corpus**, donc BM25 verse sa queue de candidats faibles dans la fusion et un document
  moyen chez les deux dépasse un excellent chez un seul → **artefact de taille**, pas défaut de RRF.
  Reranking vs RRF = **5–7**, avec 3 chutes rang 1 → absent : **erratique**, signature d'un décalage
  de domaine (mMARCO = passages web ≠ prose de FAQ).
  **Décisions (ADR-0005 accepté)** : (a) `rerank_enabled = False` — 17× le coût pour une dégradation
  mesurée ; (b) défaut **hybride RRF sans reranking**, en assumant que c'est un **jugement
  d'ingénieur, pas une conclusion de mesure** (ce corpus ne départage pas) : coût nul, RRF rattrape
  un échec total quand ses pertes ne sont que des reculs de rang, et le mode de défaillance
  disparaît à l'échelle. `retrieval_pool_factor` devient le paramètre à surveiller.
- **Semaine 5 — Jour 3 (agent Résolution en LangGraph) : CODE LIVRÉ, vérif en attente.**
  Migration **`V9__draft_responses.sql`** (§4) : `ticket_id`, `content`, `citations jsonb`,
  `status[PROPOSED|EDITED|SENT|REJECTED]`, `tone`, `low_confidence`, `issues[]`, `attempts`,
  `judge_score` (S5-J5), `reviewed_by` (S5-J4). **Pas d'unicité par ticket** : l'historique des
  re-générations est conservé, comme `annotations` conserve les corrections — c'est ce qui
  permettra de mesurer le taux de rejet en S5-J5.
  **Graphe** `app/agents/resolution.py` : `retrieve → generate → self_check →` arête conditionnelle
  `{retry → generate | accept/give_up → persist}`, état **typé** (`ResolutionState`), checkpointer
  `MemorySaver`, `thread_id = ticket-<id>`. Borne à **3 générations** (1 + 2 re-générations, §5.2).
  **`app/agents/citations.py`** (pur, sans dépendance) : marqueurs `[n]` bornés par le nombre de
  passages — demander au modèle de recopier un id de fragment inviterait l'hallucination, un petit
  entier se vérifie par appartenance ; `is_abstention()` évite d'exiger une citation d'un brouillon
  qui reconnaît honnêtement ne pas savoir (sinon la boucle le force à inventer une source).
  **Ordre du self-check = décision de coût** : contrôle déterministe des citations d'abord (gratuit),
  vérification sémantique LLM ensuite — un brouillon citant une source inexistante est rejeté sans
  dépenser l'appel. Les reproches sont **réinjectés** dans le prompt de re-génération (sinon on
  régénère à l'identique). `app/agents/store.py` : persistance asyncpg résiliente (un brouillon non
  persisté reste renvoyé dans la réponse HTTP). Endpoint **`POST /agents/resolution`** (§6) +
  schémas `ResolutionRequest` / `DraftResponse` / `Citation`.
  **Écarts assumés** : (a) **pas de nœud rerank** — mesuré et désactivé en S5-J2 (ADR-0005) ; s'il
  était réactivé il s'appliquerait *dans* `retrieval.search`, donc à l'intérieur du nœud `retrieve`,
  le graphe n'a pas à le savoir ; (b) **pas de tickets résolus similaires** comme seconde source
  (§5.2) : la table `tickets` **ne stocke aucune réponse d'agent**, citer un ticket résolu citerait
  la plainte d'origine — source trompeuse. Dépendance notée, pas oubli ; (c) `MemorySaver` et non
  `AsyncPostgresSaver` : la durabilité n'a d'intérêt que si un nœud attend une action humaine, ce
  qui sera le cas au J4 — à réévaluer alors ; (d) **imports paresseux** de LangGraph et de la
  passerelle LLM : le service démarre sans elles, et surtout les briques déterministes restent
  testables sans pile d'inférence — une garantie qu'on ne peut pas tester sans clé d'API n'en est
  pas une.
  **Vérifié** : 48 tests Python verts (15 nouveaux sur citations/routage/nettoyage), `ruff check
  app tests` vert, module importable sans langgraph ni litellm, topologie du graphe contrôlée.
  **VÉRIFIÉ par firas** : ticket 10020 (double débit), ton `empathetic` → brouillon en français
  ouvrant sur « Je comprends votre frustration », citation `[1]` → fragment 67
  *faq-facturation.md, Facturation et paiements > Double débit*, `attempts=1`,
  `low_confidence=false`, `issues=[]`, `passages_used=5`, `draft_id=1` persisté. Chaîne
  retrieve → generate → self_check → persist fonctionnelle de bout en bout.
  **FINDING non corrigé, à mesurer en S5-J5** : dans le brouillon obtenu, la 2ᵉ phrase énonce deux
  faits chiffrés (« 7 jours ouvrés », « sous 72 heures ») **sans marqueur**, alors que le prompt
  exige un marqueur par affirmation factuelle. Les faits viennent bien du passage [1] — le brouillon
  n'est pas faux — mais il y a un **écart entre la règle énoncée et ce qui est contrôlé** : le
  contrôle déterministe vérifie que *des* citations existent et sont dans les bornes, pas que
  *chaque* affirmation en porte une ; le contrôle LLM vérifie le fondement, pas le marquage.
  Correctif possible (heuristique : phrase contenant un chiffre → marqueur obligatoire) **non
  appliqué volontairement** : après l'épisode du reranking (S5-J2), on ne durcit pas une règle sans
  savoir combien de brouillons sont concernés ni si cela améliore autre chose que le nombre de
  re-générations. La grille du LLM-as-judge de S5-J5 (exactitude/complétude) doit le quantifier
  d'abord.
  **2ᵉ vérif — ton `formal` (ticket 10015)** : registre nettement différent (« Bonjour. » factuel vs
  « Je comprends votre frustration »), **les deux** phrases factuelles portent `[1]`. Le finding
  ci-dessus n'est donc **pas systématique mais intermittent** — ce qui confirme qu'il fallait
  mesurer avant de durcir la règle.
  **3ᵉ vérif — abstention (ticket 10024, hors périmètre FAQ) : BUG TROUVÉ ET CORRIGÉ.** Le brouillon
  était correct (aucune invention, 0 citation) mais `attempts=3`, `low_confidence=true`,
  `issues=[no_citation]`. Cause : `is_abstention()` cherchait « pas **d'information** » alors que le
  modèle a écrit « les **informations** … ne sont pas disponibles » — ordre des mots inversé, motif
  raté. Coût : 2 re-générations inutiles **et** une fausse alerte sur un brouillon irréprochable
  (les fausses alertes apprennent à ignorer les vraies).
  **Correctif — pas de course aux formulations** : le prompt demande désormais un **jeton explicite**
  `[NO_ANSWER]` en tête de réponse quand le modèle s'abstient → détection **exacte et indépendante
  de la langue** ; les motifs regex restent en **repli** (élargis) si le jeton est omis ; le jeton
  est retiré avant persistance et affichage. Le nœud `self_check` **court-circuite** en cas
  d'abstention (rien à vérifier : le brouillon n'affirme rien). Nouveau champ **`abstained`** dans
  `DraftResponse` et l'état : une abstention est un **résultat correct**, à distinguer d'un
  brouillon incertain — l'UI du J4 affichera « rien à proposer » et non « attention, vérifiez ».
  Une panne LLM reste `abstained=false` + `issues=[llm_unavailable]`.
  **RÉGRESSION QUE J'AI INTRODUITE, puis corrigée** : après le correctif, les métriques étaient
  bonnes (`abstained=true`, `attempts=1`, `low_confidence=false`) mais le **texte s'est dégradé** —
  « Je suis là pour vous aider à la place où vous me contactez », du remplissage grammaticalement
  cassé. Cause : ma consigne « followed by one short polite sentence » était assez vague pour que le
  modèle la remplisse de vide, là où la version d'avant produisait un texte informatif.
  **Correctif de fond** : le prompt demande désormais `[NO_ANSWER]` **et rien d'autre**, et le texte
  du refus est **écrit par le code** (`_no_passage_reply`). Le modèle a un seul jugement à rendre —
  « puis-je répondre à partir de ces passages ? » — et il le rend bien ; rédiger un refus n'en
  demande aucun, c'est toujours le même message. **Règle générale du projet ainsi formulée : le
  modèle là où il y a un jugement, du code partout ailleurs.**
  5 tests de régression ajoutés (dont la formulation exacte qui a échoué) → **53 tests verts**.
  **Note d'environnement** : PowerShell 5 affiche l'UTF-8 en CP1252 (`Ã©`) — problème de console,
  pas de données. `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8` avant les tests.
- **Semaine 5 — Jour 4 (UI brouillon + workflow de validation) : CODE LIVRÉ, vérif en attente.**
  Migration **`V10__draft_review.sql`** — 3 colonnes, chacune pour une raison distincte :
  (a) **`final_content`** : la correction humaine vit **à côté** de `content`, jamais par-dessus.
  Écraser aurait fait noter l'agent, pas le modèle, par le juge de S5-J5 ; et l'écart entre les deux
  mesure **combien** il a fallu réécrire — bien plus parlant qu'un taux de validation nu.
  (b) **`reviewed_at`** : `reviewed_by` disait qui, `created_at` quand le brouillon est né ; il
  manquait le **délai proposition → décision**, seule mesure qui réponde à « ça fait gagner du
  temps ? ». (c) **`abstained`** : sans elle, une abstention relue en base est indiscernable d'un
  brouillon ordinaire — l'UI proposerait « Valider » sur un texte disant « je n'ai pas trouvé »,
  c'est-à-dire proposerait de l'envoyer au client. `store.save` (ai-service) l'écrit désormais.
  **Backend `drafts/`** : `DraftStatus` = **machine à états explicite** (PROPOSED → EDITED → SENT |
  REJECTED, terminaux non rejouables) ; `DraftRepository` (JdbcTemplate — table écrite par FastAPI,
  même frontière qu'`analyses`/`kb_documents` ; jsonb `citations` désérialisé par Jackson, jamais
  critère de requête) qui **réhydrate chaque citation avec le passage complet** depuis `kb_documents`
  (un extrait tronqué à 280 car. peut couper la clause qui nuance — « sous réserve que… » — et
  l'agent validerait sur une source amputée) et marque **`stale`** quand le fragment a disparu (les
  id changent à chaque ré-import, S5-J1) ; `DraftClient` (RestTemplate **avec délais** : connexion
  3 s, lecture 120 s — l'appel le plus lent de la plateforme, sans timeout un service IA bloqué
  immobiliserait les fils Tomcat) ; `DraftService` (génération **hors transaction** : 2 min de
  transaction ouverte pour zéro écriture épuiserait le pool) ; `DraftController` **AGENT+**, deux
  racines assumées — `/api/tickets/{id}/draft` pour demander, **`PATCH /api/drafts/{id}`** pour
  trancher (router la revue par le ticket ouvrirait une course : deux agents valideraient un texte
  qu'ils n'ont pas lu). **Garde-fou de fond** : valider une abstention → **409**, côté serveur et pas
  seulement masqué en CSS — une règle qui n'existe qu'en CSS n'est pas une règle.
  **Frontend** : `shared/citations.ts` = `splitCitations()` **pure** (texte → segments) plutôt qu'un
  `innerHTML` avec `replace` — le brouillon dérive du **corps du ticket**, donc du client ; produire
  des données et laisser le gabarit produire les nœuds ferme l'injection au lieu de s'en remettre à
  l'assainissement. `draft-panel.component` : citations **cliquables ouvrant le passage en place**
  (et non un lien vers l'écran KB, réservé ADMIN — un agent y serait refusé, et vérifier ne doit pas
  coûter de quitter ce qu'on lit), édition/validation/rejet, ton segmenté formel|empathique, bandeau
  faible confiance, **état abstention neutre** (ni jaune ni rouge : colorer en alerte apprendrait que
  « pas de réponse » est un incident). Panneau placé **sous le message**, colonne large — 320 px de
  rail ne permettent aucune relecture sérieuse. **Garde anti-réponse périmée** sur le chargement.
  **45 clés i18n FR/EN** (414/414 à parité, 0 chaîne en dur).
  **Vérifié** : 53 tests Python verts, `ruff` vert, **`ngc --noEmit` avec `strictTemplates` vert**,
  SCSS compilé, `splitCitations` exécutée sur 9 cas (dont réassemblage sans perte et idempotence
  `lastIndex`) — tous verts ; `citations.spec.ts` ajouté ; arité/accolades Java contrôlées.
  `DraftIntegrationTest` (12 cas) teste la **machine à états**, pas la génération : générer demande
  un LLM, le résultat varie, l'assertion serait instable et la CI dépendrait d'une clé d'API.
  **`httpclient5` ajouté en `test`** : sans lui `TestRestTemplate` retombe sur HttpURLConnection dont
  la liste de méthodes est codée en dur et **ne contient pas PATCH**. Laisser un défaut d'outillage
  dicter la forme de l'API aurait été le mauvais sens de la dépendance.
  **À vérifier par firas** : `mvn verify` vert, `docker compose up -d --build backend` (Flyway V10),
  `docker compose up -d --build ai-service`, `ng serve` → ouvrir 10020 → Proposer une réponse →
  cliquer `[1]` → passage source ; corriger puis valider ; ouvrir 10024 → « Rien à proposer » sans
  bouton Valider.

- **Semaine 5 — Jour 5 (LLM-as-judge + qualité RAG chiffrée) : CODE LIVRÉ, exécution en attente.**
  **`app/agents/judge.py`** : grille **exactitude / complétude / ton** en niveaux **0-1-2 ancrés**
  sur des cas observables (et non une note sur 5 — une échelle fine sans définition partagée produit
  du bruit déguisé en précision : le même brouillon reçoit 3 ou 4 selon l'appel). Verdict validé
  **Pydantic** (convention §3), parsing tolérant sur la forme (bloc de code, bavardage) mais
  **strict sur le fond** — une note hors barème est un refus, pas une valeur à redresser.
  **`aggregate` verrouille sur l'exactitude** : note = 0 dès que l'exactitude est nulle, sinon
  moyenne/6. Une moyenne arithmétique donnerait **0,67** à un brouillon bien écrit qui invente un
  délai de remboursement — un chiffre rassurant sur un texte à jeter. **Abstentions non notées**
  (`is_judgeable`) : les noter donnerait complétude 0 et pénaliserait le comportement recherché ;
  l'agrégat mesurerait la **couverture de la KB déguisée en qualité de rédaction**. Le taux
  d'abstention est reporté à côté, comme métrique de couverture. Le prompt du juge **cache
  `low_confidence` et `attempts`** (ils prédisent en partie la note : les montrer ferait de la
  mesure une prophétie auto-réalisatrice) et encadre les données non fiables (injection).
  **Passerelle LLM** : `complete_with_model()` ajouté + paramètre `groq_model` → le juge tourne en
  **70b, le rédacteur en 8b** (biais d'auto-préférence ; même séparation qu'au filtre d'accord
  S2-J5). Le modèle réellement utilisé est **remonté** (`judged_by`) — un chiffre obtenu avec un
  modèle de repli ne se compare pas à un chiffre obtenu avec le modèle prévu.
  **`store.set_judge_score`** : écriture **en place** (seule exception à la règle d'ajout du module
  — une note est une mesure, pas une décision d'historique) ; `Decimal` et non `float` (asyncpg
  refuse un flottant sur `NUMERIC`).
  **`eval/judge_drafts.py`** (dans le conteneur) : **échantillon stratifié par catégorie** (un
  tirage uniforme sur 10 000 tickets serait dominé par la catégorie la plus fréquente et masquerait
  qu'on est excellent sur un sujet et muet sur un autre), génération si le brouillon manque,
  jugement, écriture de `judge_score`, **reprenable** (brouillon déjà noté = ignoré — leçon du S3-J5
  où ~80 appels ont manqué de budget). Rapport `eval/results/judge_s5j5.md` : vue d'ensemble, note
  par critère, **taux de brouillons inutilisables**, **écart de note signalés / non signalés**,
  ventilation par catégorie, **5 pires cas nommés** (leçon S5-J2 : l'agrégat masque tout).
  **ADR-0006 : décision PRÉ-ENREGISTRÉE** — les règles sont écrites *avant* les chiffres, avec un
  seuil chiffré (`Δ ≥ 0,15` garder / `< 0,05` retirer le bandeau / groupe < 5 → **ne rien
  conclure**). Motif explicite : après le reranking (S5-J2) et la régression du J3, on ne se laisse
  pas la possibilité de rationaliser après coup.
  **CI** : les parties **déterministes** du juge entrent en CI (`tests/test_judge.py`, 16 cas, job
  `ai-service`) ; la campagne complète n'y entre pas — elle échouerait rouge sur un quota épuisé, et
  *un rouge qui n'indique aucun défaut du code apprend à ignorer les rouges*. Tableau CI/manuel
  ajouté à `eval/README.md`.
  **Vérifié** : **68 tests Python verts**, `ruff` vert sur `app`/`tests`/`judge_drafts.py`, et le
  **générateur de rapport exécuté sur données de synthèse** (50 lignes + 3 cas limites : que des
  abstentions, groupe vide, échec seul) — un plantage du rapport après 100 appels de modèle aurait
  coûté la campagne entière.
  **Aucun changement d'interface** : le bandeau « à relire » existe depuis le J4 ; `judge_score`
  n'est **pas** affiché à l'agent (métrique d'évaluation hors ligne, elle ne change aucune de ses
  actions — l'afficher serait du jargon).
  **1ʳᵉ EXÉCUTION par firas — 3 défauts trouvés, dont 2 de ma part, tous corrigés.**
  Résultat brut : **8 tickets seulement** (au lieu des 50 demandés), 7 notés, **note moyenne 0,90**,
  0 brouillon inutilisable, 1 abstention. Chiffres encourageants mais **non concluants** — 7
  brouillons ne mesurent rien.
  (a) **Échantillon plafonné à 8** : `pick_tickets` exigeait `JOIN analyses`, or seuls ~8 tickets
  sont analysés — l'import de 10 000 est antérieur au câblage du triage. Le filet de sécurité
  portait le même JOIN, donc le même plafond. Corrigé : **jointure externe**, `NON_ANALYSE` devient
  une **strate à part entière** (l'agent rédige depuis le sujet et le corps, l'analyse ne sert qu'à
  cadrer le ton), et la strate non analysée est échantillonnée **par pas fixe** (`id % 137`) plutôt
  qu'en prenant les N premiers — les tickets voisins sortent du même modèle du générateur, on
  noterait dix fois la même formulation en croyant mesurer dix cas. Pas déterministe = reprise
  gratuite.
  (b) **La reprise appauvrissait le rapport** : la 2ᵉ exécution a écrasé un rapport détaillé par un
  rapport dont la table par critère était vide (`—`). Cause : la branche de reprise relisait
  `judge_score` en base, qui ne contient que l'agrégat. **L'agrégat se déduit des trois critères,
  l'inverse est faux** — on perdait le taux d'exactitude nulle, le seul chiffre qui décide d'un
  déploiement. Corrigé : **journal `eval/results/judge_s5j5.jsonl`** écrit ligne à ligne (pas en fin
  de campagne : un quota épuisé ferait tout perdre) et devenu la source de reprise. Le détail est un
  artefact d'évaluation, il n'a rien à faire dans le schéma applicatif ; `judge_score` reste en base
  pour l'application.
  (c) **Le rapport invitait à lire un chiffre que l'ADR interdit d'utiliser** : il affichait
  « écart **−0,11** » sur des groupes de 1 et 6, alors que l'ADR-0006 exige ≥ 5 par groupe. Un seuil
  pré-enregistré qu'on affiche quand même est un seuil contourné par sa propre présentation.
  Corrigé : sous le seuil, le rapport écrit « **Aucune décision possible** » et **n'affiche pas**
  l'écart. Le rapport signale aussi désormais l'écart **demandé / obtenu** (8 pour 50), qui était
  passé en silence.
  **Revérifié** : 68 tests verts, `ruff` vert, générateur de rapport rejoué sur le cas réel de firas
  (assertions : règle d'effectif appliquée, écart signalé, critères présents).
  **CAMPAGNE COMPLÈTE EXÉCUTÉE ET VÉRIFIÉE (50 tickets) — ADR-0006 accepté.**
  Chiffres : 34 notés / **16 abstentions (32 %)** / 0 échec. **Exactitude 1,71 · complétude 1,03 ·
  ton 2,00** → note moyenne **0,78** (médiane 0,83). **1 brouillon inutilisable sur 34 (3 %)**.
  **Δ faible confiance = +0,10** (18 signalés à 0,73 contre 16 non signalés à 0,83).
  **Lectures qui comptent** : (a) **la complétude est le vrai défaut** (1,03/2 = un brouillon sur
  deux ne traite qu'une partie de la demande) et il est **de recherche, pas de rédaction** — les
  pires cas sont des tickets à deux sujets dont les 5 passages remontés couvrent tous le sujet
  dominant ; piste *décomposition en sous-questions* identifiée mais **non implémentée** (change la
  forme du nœud `retrieve`, doit être mesurée à part) ; (b) **le ton ne discrimine pas** — 2,00 sur
  34, variance nulle, donc il ajoute mécaniquement 0,33 à chaque note : sur les deux critères qui
  varient la moyenne vaut **0,69** et non 0,78. Les campagnes suivantes reporteront exactitude et
  complétude **séparément** ; (c) **3 % d'inutilisables** = l'argument chiffré en faveur de la
  boucle humaine du J4 ; (d) 32 % d'abstention = **couverture** de la KB, avec un artefact de
  sélection à assumer (0 abstention chez les tickets analysés, écrits autour des sujets de la FAQ,
  contre 15/42 chez les importés).
  **Décision 2 appliquée telle qu'écrite** : Δ tombe dans la bande [0,05 ; 0,15[ → le bandeau
  `banner--warning` est **rétrogradé en mention discrète** (`draft__flag`, gris, icône `info`),
  libellé neutralisé FR/EN. Valeur du pré-enregistrement : la règle a choisi l'option intermédiaire,
  que je n'aurais probablement pas retenue en regardant les chiffres après coup (tentation de garder
  — « +0,10, ça marche » — ou de supprimer — « +0,10, c'est du bruit »).
  **Revérifié après le changement d'UI** : `ngc --noEmit` strictTemplates vert, SCSS compilé,
  414/414 clés à parité.
  **Reste pour firas** : Démo 5, commit + push.

- **Semaine 6 — Jour 1 (guardrails text-to-SQL) : CODE LIVRÉ, vérif en attente.**
  Principe directeur, écrit dans l'**ADR-0007** : le text-to-SQL est une **injection SQL
  délibérée** (on exécute un texte d'origine incontrôlée). La question n'est donc pas « comment
  empêcher le modèle de mal se comporter » mais « que se passe-t-il quand il le fait ». Réponse :
  **deux barrières indépendantes, dont aucune n'est censée suffire.**
  **Barrière 1 — `app/agents/sql_guard.py` (sqlglot, AST).** Un seul ordre (2 éléments après
  `parse` = enchaînement par `;` → refus) ; racine `Select|Union|Subquery` ; **aucun nœud
  d'écriture nulle part dans l'arbre** — ce qui attrape la **CTE écrivante**, dont la racine est un
  SELECT irréprochable ; relations limitées aux 6 vues (CTE reconnues comme noms locaux) ; schémas
  système refusés ; fonctions d'évasion refusées (`pg_sleep`, `dblink`, `pg_read_file`,
  `set_config`) ; `LIMIT` imposé, plafond 500 ; **sortie régénérée depuis l'arbre**, jamais la
  chaîne d'entrée. *Pas de liste de mots interdits* : elle raisonne sur des caractères là où la base
  raisonne sur une grammaire (`DEL/**/ETE`, casse mélangée, ou requête sans mot interdit lisant
  `users` en sous-requête), et produit des faux positifs incompréhensibles.
  **Barrière 2 — `V11__insight_views.sql` + `app/agents/insight_db.py`.** Rôle PostgreSQL
  `insight_ro` : `SELECT` sur 6 vues seulement, aucun droit sur les tables,
  `default_transaction_read_only=on` et `statement_timeout=5s` **au niveau du rôle** ; **pool
  asyncpg distinct** avec les mêmes réglages en session + transaction `READ ONLY` explicite. Mot de
  passe par **placeholder Flyway** (`spring.flyway.placeholders.insight_password` ←
  `INSIGHT_DB_PASSWORD`, défaut `insight`) — une migration est versionnée, un secret ne l'est pas.
  **Vues sans donnée personnelle** : `v_tickets`, `v_daily_volume`, `v_draft_activity` excluent
  `customer_email`, `body` et le texte des brouillons. Motif principal : ces valeurs seront
  **réinjectées dans un prompt** de synthèse au J2 — le client deviendrait auteur d'une partie de
  l'instruction. `subject` conservé (sans lui un résultat n'est qu'une liste d'identifiants).
  `age_hours` pré-calculé (les intervalles sont ce sur quoi un text-to-SQL se trompe).
  **Agent** `app/agents/insight.py` : schéma des vues **écrit à la main** dans le prompt (le modèle
  a besoin des *valeurs possibles*, pas des types), question bornée à 500 car., `IMPOSSIBLE` si
  hors périmètre, motif de refus **journalisé mais pas renvoyé** (il dirait à un attaquant quelle
  barrière il vient de heurter). Endpoint `POST /agents/insight` + `InsightRequest/InsightResponse`
  (§6) ; `user_role` accepté par contrat mais **n'est pas une autorisation** — le RBAC MANAGER+ est
  côté Spring. Réponse NL et `chart_spec` : J2.
  **Livrable « SQL malveillant systématiquement bloqué » = 2 suites, une par barrière** :
  `tests/test_sql_guard.py` (**44 cas**, groupés par *mécanisme d'attaque* et non par mot-clé) et
  `InsightRoleIntegrationTest` (**8 cas**, Testcontainers) qui se connecte **directement en
  `insight_ro`** — comme le ferait une requête ayant contourné la barrière 1 — et vérifie que
  `users`, `tickets`, `refresh_tokens`, `kb_documents`, `draft_responses` restent inaccessibles.
  **DÉFAUT TROUVÉ EN ÉCRIVANT LES TESTS** : sqlglot **conserve les commentaires** au rendu. Il
  convertit `-- ligne` en `/* bloc */`, ce qui sauve la mise — sans cette conversion, un `--` en fin
  de requête aurait **neutralisé le `LIMIT` ajouté juste après**, et le plafond de lignes aurait
  sauté *sans lever la moindre erreur*. Rendu passé en `comments=False` + test de régression : une
  garantie de sécurité ne doit pas dépendre d'un détail d'implémentation d'une lib tierce.
  **Vérifié** : **112 tests Python verts**, `ruff` vert, arité/accolades Java contrôlées,
  `sqlglot>=25` ajouté aux requirements, `INSIGHT_DB_PASSWORD` dans `.env.example` et compose.
  **VÉRIFIÉ par firas — les deux barrières tiennent, la qualité du SQL est le sujet du J2.**
  Chaîne complète : V11 appliquée (`now at version v11`), `health/ready` → `insight_readonly: up`,
  question → SQL généré → exécuté → 7 lignes renvoyées, `truncated=false`. **Barrière 2 prouvée
  hors application** : `psql -U insight_ro -c "SELECT email FROM users"` → `ERROR: permission denied
  for table users`. C'est la démonstration à faire en soutenance — aucun code du projet n'intervient,
  c'est PostgreSQL qui refuse.
  **MAIS le SQL généré était faux** : le modèle a fait `UNION ALL` entre `v_tickets` (grain : un
  ticket) et `v_category_trends` (grain : un jour × catégorie), mélangeant deux granularités →
  `null → 10016`. La garde a bien fait son travail (vues whitelistées, ordre unique, LIMIT posé) ;
  c'est la **qualité**, pas la sécurité, qui a échoué — et c'est exactement l'objet du J2 (suite de
  30 questions, ≥ 80 %). Deux causes identifiées, dont une de ma part :
  (a) `schema_description()` listait les colonnes **sans jamais dire le grain** de chaque vue — sans
  cette information un modèle ne peut pas savoir qu'unir deux vues est absurde. Corrigé : `GRAIN :`
  sur chaque vue, interdiction explicite de l'UNION entre vues, mention que `tickets` est déjà un
  décompte (`SUM`, pas `COUNT(*)`), et **2 exemples question → SQL**.
  (b) **Incohérence que j'ai introduite** : `v_tickets.category` vaut NULL pour un ticket non
  analysé alors que `v_daily_volume.category` vaut `'NON_ANALYSE'`. Signalée dans le prompt, **non
  corrigée en base** — changer une vue demande une V12, et je refuse de migrer sur une seule
  observation après l'épisode du reranking. À trancher au J2 avec la suite d'éval.
  **Correctif Dockerfile** (voir §7) : `--no-cache-dir` faisait retélécharger ~2 Go (torch) à chaque
  ligne ajoutée à `requirements.txt` → 34 min puis timeout. Remplacé par un cache BuildKit.

- **Semaine 6 — Jour 2 (réparation SQL + synthèse + suite d'éval) : CODE LIVRÉ, exécution en
  attente.** ⚠ **Sandbox Linux indisponible ce jour-là** : aucun test n'a pu être exécuté de mon
  côté (ni `pytest`, ni `ruff`). Tout est vérifié par relecture uniquement — c'est le livrable le
  moins garanti du projet à ce stade, `mvn`/`pytest` de firas font foi.
  **Défaut trouvé avant de coder** : `schema_description()` annonçait une colonne `tickets` sur
  `v_category_trends` et `v_hourly_load`, qui exposent en réalité `ticket_count` (V5). Le modèle
  aurait généré du SQL invalide à chaque question sur ces vues. Corrigé.
  **`app/agents/insight.py` réécrit en graphe LangGraph** : `generate → execute → {retry |
  synthesize | give_up}`, état typé `InsightState`, **3 générations maximum**. L'erreur PostgreSQL
  (`column "tickets" does not exist`) est **réinjectée dans le prompt** — un text-to-SQL se trompe
  surtout sur des détails que la base sait nommer précisément. Erreur **tronquée à 300 car.**
  (un message PostgreSQL peut contenir la requête entière et noyer la consigne). Les refus de la
  garde sont traduits en consignes actionnables (`_explain`) : « relation_not_allowed » n'apprend
  rien au modèle, « la vue users n'existe pas pour vous » le remet sur les rails. **Pas de
  checkpointer** (contrairement à Résolution) : une question de manager est instantanée et sans
  suite, conserver l'état coûterait de la mémoire pour une reprise que personne ne demandera.
  **`app/agents/chart.py` — le graphique est déduit par le CODE, pas par le modèle.** Application
  directe de la règle du S5-J3 : choisir un type de graphique est une table de décision sur
  (nb de lignes, type de la colonne d'axe, nb de colonnes numériques), aucun jugement. Le confier au
  modèle ajouterait 3 modes de défaillance (colonne inventée, type inexistant, JSON cassé) pour zéro
  gain. **Jamais de camembert** : un anneau affirme que les valeurs sont *les parts d'un tout*, ce
  que le code ne peut pas vérifier — « tickets par catégorie » l'est, « délai moyen par catégorie »
  pas du tout, et les deux ont la même forme de résultat. `type = "none"` porte toujours un `reason`
  pour que l'UI du J3 écrive « une seule valeur » au lieu d'afficher un cadre vide.
  **Erreur trouvée en écrivant les tests** : `hour_of_day` est un entier, donc il était classé comme
  *mesure* et non comme *axe* → « tickets par heure » ne produisait aucun graphique. Corrigé : le
  temporel est classé **avant** le numérique.
  **Synthèse en langage naturel** : 2 phrases max, lignes du résultat bornées à 30 dans le prompt et
  **marquées non fiables** (`v_tickets.subject` est écrit par le client — c'est le seul texte libre
  qui traverse les vues). Une panne de synthèse ne fait **pas** échouer la requête : les lignes et le
  SQL restent exploitables.
  **Suite d'éval** : `eval/datasets/insight_questions.jsonl` = **30 questions**, dont **3 attendant
  un refus** (adresse client, corps du message, salaires — un agent qui y répond est plus dangereux
  qu'un agent qui se trompe de colonne). `eval/eval_insight.py` compare **les résultats d'exécution,
  jamais le texte du SQL** (`COUNT(*) FROM v_tickets WHERE status='NEW'` et `SELECT new_tickets FROM
  v_ticket_stats` sont tous deux justes). Deux niveaux reportés : **strict** (ordre des colonnes
  compris) et **souple** — l'écart mesure les réponses justes mal présentées. Le SQL de référence
  passe **lui aussi par la garde** : une référence qui ne respecterait pas les règles imposées au
  modèle serait un barème injuste.
  **Les 2 questions d'exemple du prompt sont exclues de la suite** — au S6-J1 le modèle a recopié mot
  pour mot l'exemple que je venais d'ajouter, ce qui rendait la vérification sans valeur.
  **EXÉCUTÉ ET VÉRIFIÉ — objectif atteint. 130 tests verts, `ruff` vert.**
  Deux exécutions consécutives : **27/30 (90 %)** et **26/30 (87 %)**, objectif §9 ≥ 80 % tenu.
  **DÉCOUVERTE MAJEURE — la mesure était du bruit.** Les trois premières exécutions donnaient
  73 / 77 / 73 %, avec **11 verdicts sur 30 qui basculaient** d'une exécution à l'autre. Cause :
  `temperature` jamais fixée, donc échantillonnage à 1,0 par défaut chez Groq. Comparer deux scores
  n'avait aucun sens, et j'avais analysé des échecs dont une partie était du hasard. Correctif :
  paramètre `temperature` ajouté à la passerelle, **`temperature=0` sur la génération SQL et la
  synthèse** — traduire une question en SQL a *une* bonne réponse, la variation n'est un service
  rendu que quand le texte s'adresse à un humain. Résultat : **29 verdicts sur 30 identiques** entre
  deux exécutions. Le résidu est attendu (temperature=0 ne rend pas l'inférence bit-à-bit
  déterministe : lots GPU).
  **Deux questions de la suite étaient invalides — mes bugs, pas ceux de l'agent** : (a) #16
  `AVG(age_hours)` — le SQL généré était *identique* à la référence, mais `age_hours` dérive de
  `now()`, donc deux exécutions à quelques secondes d'écart donnent des moyennes différentes. **Une
  référence non reproductible n'est pas une référence.** Remplacée par une question stable ;
  (b) #19 formulation ambiguë. Corriger ces deux-là ne renforce pas l'agent, ça répare l'instrument.
  **Trois défauts de prompt corrigés, diagnostiqués sur le SQL généré** : (a) `COUNT(*)` sur des
  vues **déjà agrégées** (#11, #12) — comptait les heures, pas les tickets ; l'avertissement était
  enfoui par vue, il est devenu un bloc en tête ; (b) une question nommant **une** valeur est un
  filtre, pas une répartition (#15) ; (c) `was_edited` glosé (#24 le confondait avec `reviewed_by`).
  **Leçon sur le few-shot** : #15 échouait parce que la question ressemblait lexicalement à mon
  exemple « tickets par catégorie » — **un exemple crée un bassin d'attraction**. Corrigé par une
  règle générale, pas par un contre-exemple, qui aurait été du sur-apprentissage sur la suite.
  **Défaut du harness, le même qu'au S5-J5** : le rapport disait « résultat différent (5 lignes
  contre 1) » **sans montrer le SQL généré**. Impossible de diagnostiquer sans tout rejouer. Corrigé :
  chaque échec affiche le SQL généré et celui de référence, en entier.
  **3 échecs résiduels, assumés et non corrigés** : #6 (`category = 'HIGH'` au lieu de `priority` —
  vraie erreur du 8b, la description est pourtant explicite), #19 (`SUM(attempts)` au lieu de
  `COUNT(*)` — la question reste ambiguë, et **je cesse de la réécrire** : éditer la suite jusqu'à
  ce que tout passe détruit sa valeur), #30 (a répondu `SELECT * FROM v_tickets` au lieu de refuser).
  **Le refus est le maillon faible** : 2/3 puis 3/3 selon l'exécution. Conséquence **sécurité nulle**
  (le rôle ne lit que les vues autorisées) mais conséquence **confiance réelle** — répondre à une
  question voisine est indétectable par le manager. Mitigation prévue au J3 : le SQL affiché en mode
  transparent rend la substitution visible.
  **Caveat d'honnêteté à dire en soutenance** : la suite a servi **à la fois** à mesurer et à guider
  les correctifs. Les 87-90 % sont donc optimistes. Une mesure propre demanderait des questions
  jamais utilisées pour l'ajustement.

- **Semaine 6 — Jour 3 (UI Chat Insight) : CODE LIVRÉ, vérif en attente.** ⚠ **Sandbox Linux
  toujours indisponible** — ni `ngc`, ni `pytest`, ni parité i18n vérifiés de mon côté.
  **Backend `insight/`** : `InsightController` (`POST /api/insight/questions`, **MANAGER+** — ces
  vues agrègent l'activité de toute l'équipe, un agent y verrait le volume traité par ses
  collègues), `InsightClient` (RestTemplate, connexion 3 s / lecture 90 s — jusqu'à 4 appels de
  modèle par question ; sans expiration un service IA bloqué immobiliserait un fil Tomcat par
  question), `InsightAnswer` (miroir), `InsightException` → ProblemDetail **en conservant 422 vs
  503** (un refus n'est pas une panne, et l'aplatir en 500 les rendrait indiscernables).
  **`InsightRateLimiter`** : ferme la dette notée dans l'ADR-0007. Quota **par utilisateur** (et non
  par IP — derrière un NAT d'entreprise, tout le monde partage l'adresse), 30 questions/heure,
  **remplissage progressif** plutôt qu'en bloc : un rechargement horaire laisserait tout
  reconsommer d'un coup à la minute pile, le remplissage continu dégrade au lieu de couper.
  Bucket4j déjà présent (webhook S2-J4), état en mémoire — Redis si multi-instance.
  **Frontend `features/insight/`** — trois partis pris :
  (a) **la source est toujours visible, la requête toujours accessible**. Chaque réponse affiche en
  clair ce qui a été lu (« les tickets », « les volumes quotidiens ») et laisse ouvrir le SQL exact.
  Ce n'est pas de la transparence décorative : la mesure du S6-J2 a montré que l'agent répond
  parfois à une question **voisine** de celle posée (`subject` substitué au corps du message).
  Aucune barrière technique ne détecte ça — montrer ce qui a été lu, si. C'est la mitigation
  produit du seul défaut résiduel du J2.
  (b) **c'est un historique, pas une conversation**. L'agent n'a aucune mémoire d'échange (pas de
  checkpointer, décidé au J2). L'écran ne simule donc ni interlocuteur ni « en train d'écrire », et
  une ligne sous la saisie dit que chaque question est traitée séparément. Promettre une relance
  (« et le mois dernier ? ») qui ne fonctionne pas coûterait plus cher que de ne pas l'offrir.
  (c) **un résultat sans graphique n'est pas un échec** : le `reason` du J2 est affiché (« la forme
  de ce résultat ne se prête pas à un graphique ») au lieu d'un cadre vide, qui se lit comme une
  panne. Idem pour `truncated`, affiché explicitement.
  Le **type** de graphique vient du serveur ; l'interface ne fait que l'habiller aux couleurs du
  thème — décider ici dupliquerait une règle métier dans deux langages. Couleurs de catégorie
  reprises du dashboard, accent pour tout le reste (inventer une couleur par valeur ferait croire à
  un code couleur inexistant). Route `/insight` roleGuard MANAGER, entrée de nav, commande dans la
  palette, **33 clés i18n FR/EN**.
  **VÉRIFIÉ par firas** : chaîne complète de bout en bout — phrase de synthèse correcte, graphique
  à barres, tableau, « Lu depuis les tickets », « Voir la requête ».
  **BUG MAJEUR TROUVÉ ET CORRIGÉ — `RestTemplateBuilder` envoyait un corps vide.** Toutes les
  questions revenaient en 422. Le corps arrivait **vide** côté FastAPI (`Field required`, `octets=b''`),
  l'agent n'était jamais atteint. Les deux clients qui fonctionnent depuis des semaines (`KbClient`,
  `SimilarTicketClient`) utilisent `new RestTemplate()` ; les deux construits par
  `RestTemplateBuilder` — `DraftClient` et `InsightClient` — étaient cassés. Fabrique de requêtes
  (`SimpleClientHttpRequestFactory`) désormais posée **explicitement** dans les deux, délais
  conservés. **`DraftClient` n'avait jamais été exercé depuis l'interface** (le S5-J5 appelait
  l'agent directement dans le conteneur) : le bug y dormait et serait sorti à la première démo.
  **Ma fausse piste, à retenir** : j'ai d'abord diagnostiqué un problème d'encodage (ISO-8859-1 sur
  un `HttpEntity<String>` sans charset). Techniquement réel, mais **pas la cause** — un corps mal
  encodé donne `json_invalid`, pas `Field required`. J'ai bâti une théorie sur une trace tronquée
  (`— type`) au lieu d'élargir la trace. Troisième fois de la semaine ; le correctif d'encodage a été
  conservé car il se justifie seul, et le charset UTF-8 explicite a été ajouté à `KbClient` et
  `DraftClient` (`KbClient` aurait cassé sur une recherche contenant « délai »).
  **Correctif de diagnosticabilité permanent** : gestionnaire `RequestValidationError` dans
  `ai-service/app/main.py` qui journalise **les erreurs Pydantic et les octets reçus en `repr`**.
  C'est lui qui a tranché en une ligne. Il aurait dû exister depuis le premier client HTTP (S4-J4) :
  une frontière entre deux services sans journal des corps refusés est une frontière aveugle.
  Côté Spring, `InsightClient` transmet désormais le `detail` amont au lieu de le remplacer par un
  message générique (il distingue `detail` chaîne et `detail` tableau Pydantic).
  **DETTE IDENTIFIÉE — aucun test ne couvre les 4 clients HTTP.** Les tests d'intégration pointent
  tous vers `localhost:1` (port fermé) pour vérifier la **dégradation** ; personne ne vérifie qu'un
  appel réussi part correctement. C'est ce trou qui a laissé passer deux clients cassés.
  `MockRestServiceServer` assertant le corps envoyé fermerait le sujet en ~1 h — à faire avant la
  soutenance, comme lacune de couverture et non comme jour de planning.
  **Imperfections assumées** : (a) les en-têtes de colonnes sont les **alias choisis par le modèle**
  (`nb_tickets`) — ils changent à chaque question, donc intraduisibles ; simplement rendus lisibles
  par code (`Nb tickets`) plutôt que demandés au modèle, qui ajouterait un mode de défaillance pour
  un gain cosmétique ; (b) la **synthèse cite les valeurs brutes** (`FILE`, `WEBHOOK`) au lieu du
  vocabulaire produit (« Import », « Temps réel ») — le tableau de bord traduit parce qu'il connaît
  la sémantique de ses colonnes, ce qui est impossible ici où le modèle choisit ses alias.

- **DÉCISION HORS PLANNING (demandée par firas, S6-J3) — les agents n'agissent pas, sauf un cas.**
  firas a demandé si un agent pouvait clore un ticket, y répondre, etc. Réponse et arbitrage :
  (a) **jamais dans l'agent Insight** — tout l'ADR-0007 repose sur « l'agent ne peut physiquement
  pas écrire » (rôle `insight_ro`, garde AST, 52 tests, démonstration `permission denied` prononcée
  par PostgreSQL). Un agent qui lit et un agent qui agit ont des modèles de menace opposés et ne
  partagent pas de code ; (b) **l'absence d'actions est une position défendable**, pas un manque —
  « la plateforme rédige, l'humain décide » (S5-J4) ; (c) un agent d'actions généraliste coûterait
  **2 à 3 jours** (liste d'outils fermée, appels via l'API REST pour hériter du RBAC déjà testé,
  plan proposé puis confirmé, journal d'audit) et S7 n'a aucune marge.
  **Retenu** : après le J4 — qui apporte **Spring Mail** pour le digest — une **demi-journée** pour
  que le statut `SENT` envoie réellement la réponse validée au client. Ferme un manque que j'avais
  moi-même signalé au S5-J4 (« SENT = validé, bon pour envoi » faute de canal), avec la boucle
  humaine déjà construite. C'est une vraie action d'agent, démontrable, sans rien casser.
  L'agent d'actions généraliste devient la section **perspectives** du rapport.

- **Semaine 6 — Jour 4 (agent Digest) : CODE LIVRÉ, vérif en attente.** ⚠ **Sandbox toujours
  indisponible** — aucun test exécuté de mon côté.
  **Deux décisions structurantes tranchées avant de coder.**
  (1) **Où vit le PDF** : WeasyPrint est Python (service IA), Spring Mail est Java (backend). Le PDF
  traverse donc la frontière **en base64 dans la réponse HTTP**, pas par un volume partagé — un
  fichier éphémère de quelques centaines de ko ne justifie pas une dépendance de déploiement à
  recréer dans chaque environnement ; +33 % de base64 est négligeable à cette taille.
  (2) **Pas de Quartz**, contrairement au rapport §9. Quartz apporte persistance des déclencheurs,
  coordination multi-instance et rattrapage — les trois sont acquis autrement : **`UNIQUE(week_start)`**
  (V12) porte l'idempotence *et* la sûreté multi-instance (deux nœuds insèrent, un seul gagne), et
  un `@Scheduled` **horaire** à partir du lundi 8 h porte le rattrapage (si l'appli était arrêtée,
  le digest part au premier réveil). Quartz aurait coûté une dépendance et onze tables pour
  reproduire une contrainte d'unicité — et surtout aurait déplacé la vérité dans son magasin, alors
  qu'elle est mieux dans la table métier : *ce qui compte n'est pas qu'un déclencheur ait tiré, mais
  que le digest existe et soit parti*.
  **`app/agents/digest.py`** : graphe `collect → comment → render`. **Les chiffres viennent de
  requêtes SQL fixes écrites à la main**, exécutées sur le rôle **`insight_ro`** (moindre privilège
  même pour un job interne — rien n'y oblige, mais un job qui ne fait que lire n'a aucune raison de
  pouvoir écrire). Le modèle n'écrit que le **commentaire** : 3-5 puces, interdiction d'inventer un
  chiffre ou une cause, et consigne explicite qu'une variation sur petite base n'est pas une alerte.
  C'est le document que personne ne vérifiera — donc c'est là que rien de généré ne doit être
  chiffré. Comparaison semaine/semaine, `movers` en **absolu ET relatif** (+3 sur une base de 2 est
  un triplement sans importance ; +200 sur 3000 est invisible en relatif mais c'est le vrai travail
  en plus). `_variation` renvoie **`None`** quand la semaine précédente est vide plutôt que +∞ ou 100 %.
  **`digest_render.py`** : Markdown (fait foi) puis PDF WeasyPrint, **import paresseux et dégradation
  propre** — WeasyPrint dépend de pango/cairo, absents = `to_pdf` renvoie `None` et le digest part en
  texte. Dockerfile : libs système + `fonts-dejavu-core` (sans police, les accents sortent en carrés).
  **Backend `digest/`** : `DigestRepository` (`insertIfAbsent` traite `DuplicateKeyException` comme
  un **résultat normal** — vérifier « existe-t-il ? » avant d'insérer ne suffit pas, deux nœuds
  peuvent lire « non » simultanément) ; `DigestMailer` (**`ObjectProvider<JavaMailSender>`** : sans
  config SMTP le bean n'existe pas, et une injection directe empêcherait **tout le backend** de
  démarrer) ; `DigestService` (génération **hors transaction** — 3 min pour deux écritures ; envoi
  **tolérant**, un échec est *tracé* dans `send_error`, jamais propagé) ; `DigestController`
  MANAGER+, `/status` pour que l'UI **dise** qu'aucun envoi n'est configuré au lieu de le laisser
  supposer. `DigestClient` : 4ᵉ client HTTP, écrit **après** le défaut du J3 — fabrique explicite et
  charset UTF-8 dès l'écriture.
  **Le PDF n'est pas stocké** (V12) : c'est un rendu du Markdown, régénéré à la demande. Conserver un
  binaire dérivé obligerait à le migrer à chaque changement de mise en forme et à répondre « lequel
  fait foi » le jour où les deux divergent. Contrepartie assumée : un téléchargement coûte un appel.
  **Frontend** : page `/digest` MANAGER+ — historique, état d'envoi **explicite par semaine**
  (envoyée / non envoyée / échec **avec sa cause**), Markdown lisible à l'écran (c'est exactement le
  corps du courriel), téléchargement PDF **en blob** (un `<a href>` ne passe pas par l'intercepteur
  JWT et recevrait un 401). 28 clés i18n FR/EN.
  **À vérifier par firas** : `mvn verify`, `docker compose build ai-service` (nouvelles libs système,
  build long) puis `up -d --build --force-recreate backend ai-service` (Flyway V12), `npm run build`,
  puis `/digest` → « Générer maintenant ». Pour tester l'envoi : Mailpit sur le port 1025 et
  `SPRING_MAIL_HOST=mailpit`, `DIGEST_RECIPIENTS=…`.

- **Prochaine étape** : brancher l'**envoi réel de la réponse validée** (décision ci-dessus, ~½ j),
  puis **Semaine 6 — Jour 5** : budgets de tokens par run, circuit breaker quota LLM (dégradation
  Ollama), journalisation `agent_runs`. Démo 6. Voir §9.

> Mettre à jour cette section à la fin de chaque jour du planning.
> Planning complet : `SupportIQ_Rapport_Technique.md` §9 (8 semaines × 5 jours).

---

## 1. Qui je suis — l'équipe senior au complet

Selon la tâche, j'incarne le niveau **staff/senior** du domaine concerné. Être senior =
expliquer le **POURQUOI** de chaque choix en une ou deux phrases, refuser la sur-ingénierie
**autant que** les raccourcis sales, anticiper les cas d'erreur **avant** les cas nominaux,
et corriger firas (étudiant) quand il demande quelque chose de sous-optimal — il préfère
apprendre que d'avoir raison.

- **Architecte logiciel senior** — frontières entre services, contrats d'API, messaging
  asynchrone, décisions structurantes documentées en ADR.
- **Senior backend Java/Spring** — Spring Boot 3 idiomatique, Spring Security, JPA sans
  pièges N+1, Flyway, Testcontainers, ProblemDetail RFC 7807.
- **Senior frontend Angular** — architecture par features, RxJS propre, intercepteurs,
  gestion d'état justifiée, accessibilité de base.
- **Senior ML/NLP engineer** — baselines avant fine-tuning, test set gelé, métriques par
  classe, analyse d'erreurs, décisions chiffrées.
- **Senior LLM/agents engineer** — LangGraph avec état typé, sorties structurées validées
  Pydantic, guardrails, routage coût/latence, observabilité Langfuse.
- **Senior DBA PostgreSQL** — index justifiés, pgvector, vues analytiques, requêtes
  expliquées (EXPLAIN) quand la performance compte.
- **Senior DevOps** — Docker multi-stage, CI rapide et fiable, secrets jamais commités,
  healthchecks partout.
- **Senior security engineer** — OWASP top 10, JWT correctement implémenté, moindre
  privilège, prompt injection traitée comme une menace réelle.

---

## 2. Architecture en bref

**Spring Boot = plan de contrôle** (sécurité, transactions, règles métier, orchestration).
**FastAPI = plan de calcul** (NLP, agents LangGraph, embeddings). Frontière claire, contrat
OpenAPI versionné entre les deux.

```
Angular 18 SPA ──HTTPS/WSS──> Spring Boot 3 (Java 21) ──JDBC──> PostgreSQL 16 + pgvector
                                     │
                                     └──AMQP (async)──> RabbitMQ ──consume──> Service IA FastAPI
                                                                               (pipeline hybride NLP,
                                                                                agents LangGraph,
                                                                                LiteLLM gateway,
                                                                                Langfuse traces)
```

Décisions clés (détail + arguments d'entretien dans le rapport §3 et `docs/adr/`) :
- **Analyse asynchrone via RabbitMQ** — un import de 10k tickets ne bloque pas une requête
  HTTP. Spring publie `ticket.created`, FastAPI consomme et publie `ticket.analyzed`.
  Découplage, résilience (retry + DLQ), scalabilité horizontale du worker IA.
- **pgvector plutôt qu'un vector store séparé** — un seul stockage, ACID entre données
  métier et embeddings, jointures SQL directes. FAISS/Qdrant serait de la sur-ingénierie ici.
- **Routage de modèles par confiance (F1)** — classifieur local fine-tuné (~30 ms, 0 $)
  pour les cas standards, escalade LLM seulement si confiance < seuil (0.80). On mesure et
  affiche le taux d'escalade.
- **LiteLLM = passerelle unique** — interface OpenAI-compatible devant Groq → Gemini →
  OpenRouter → Ollama : failover sur quota épuisé, pas de vendor lock-in, un seul point
  d'instrumentation.

---

## 3. Conventions (non négociables)

- **Commits conventionnels** : `feat:`, `fix:`, `chore:`, `docs:`, `test:`, `refactor:`… ,
  atomiques. Une branche par jour du planning.
- **Spring — un package par module** : `auth / imports / tickets / dashboard / messaging /
  webhook` (+ `common` transverse). Pas de fourre-tout.
- **Migrations Flyway numérotées** : `src/main/resources/db/migration/V1__users_auth.sql`,
  `V2__tickets_imports.sql`… Jamais éditer une migration déjà appliquée.
- **Gestion d'erreurs Spring** : `ProblemDetail` (RFC 7807) via un `@RestControllerAdvice`
  global. Pas de stacktrace exposée, pas de map d'erreur ad hoc.
- **JPA** : `ddl-auto=validate` (Flyway est propriétaire du schéma), `open-in-view=false`,
  associations `LAZY` par défaut. Instant → `timestamptz` (Hibernate 6).
- **Python — contrats Pydantic stricts** : toute sortie LLM validée contre un schéma ;
  échec de parsing → retry avec message d'erreur injecté → fallback règles. Zéro JSON cassé
  en base.
- **Sécurité** : JWT access 15 min + refresh rotatif 7 j (hashé SHA-256, révocable), BCrypt
  cost 12. RBAC `AGENT` < `MANAGER` < `ADMIN`. Secrets jamais commités (`.env` gitignoré).
  Prompts : instruction système séparée du contenu utilisateur (mitigation prompt injection).
- **Qualité** : couverture cible 70 % sur les services métier Spring ; tests d'intégration
  Testcontainers (PostgreSQL réel) ; tests de contrat Spring ↔ FastAPI (schémas OpenAPI).

---

## 4. Règles de travail

- **Suivre le planning jour par jour** ; ne **jamais** implémenter en avance une feature
  d'une semaine future sans l'accord explicite de firas.
- **Chaque jour = une branche + des commits atomiques + son livrable vérifiable** atteint
  avant de passer au suivant.
- **Tout écart par rapport au rapport** (lib différente, choix d'implémentation non
  spécifié) : le signaler et, si structurant, rédiger un **ADR dans `docs/adr/`** avant de
  coder.
- **En fin de chaque jour** : auto-revue senior du code produit (comme la PR d'un junior :
  points forts, points faibles, ce que j'améliorerais avec plus de temps) **+ une question
  d'entretien technique** que ce travail pourrait attirer, avec la réponse attendue.
- **Tests d'abord sur la logique métier** ; jamais de code mort ni de TODO sans référence
  au jour du planning qui le résoudra.
- **Poser des questions plutôt que supposer** quand le rapport est ambigu.

---

## 5. Décisions de projet déjà prises

- **Le monorepo vit à la racine de `StageProxym`** (≡ le `supportiq/` du rapport §15) :
  squelette, `CLAUDE.md`, rapport et `.git` partagent la même racine. Écart bénin assumé.
- **Vérification Docker & git côté firas** : le sandbox d'exécution n'a pas de démon Docker,
  ni Java 21/Maven, et son mount Windows interdit `unlink`/`rename` (donc pas de git, et
  l'édition ne tronque pas : **toujours réécrire un fichier en entier**). firas lance
  Docker/git/Maven sur Windows ; je valide statiquement et je le guide.
- **Backend dockerisable (S3, post-J5)** : `backend/Dockerfile` (build Maven multi-stage, tests skippés
  car en CI) + service `backend` activé dans compose (`env_file: .env`, `DB_HOST=postgres`,
  `RABBITMQ_HOST=rabbitmq`, port 8080). But : que **tout le stack lise le même `.env`** (secret JWT, admin,
  webhook cohérents) — IntelliJ ne lit pas `.env`, donc y tournait sur le secret par défaut du profil dev.
  Dev au choix : IntelliJ (hot-reload) **ou** Docker (cohérence/démo) — pas les deux en même temps (port 8080).
- **Écarts J2 assumés** : (1) projet Spring rédigé à la main (proxy bloque `start.spring.io`) ;
  (2) `security` différée au J3 pour éviter un `permitAll` jetable ; (3) CI backend en `mvn`
  (pas `./mvnw`, aucun wrapper commité) ; (4) IDs en `BIGINT IDENTITY` (le rapport dit juste `id`).
- **Écarts J3 assumés** : (1) `/register` réservé ADMIN (pas de self-signup) + premier admin
  seedé au démarrage — cohérent §6/§7 ; (2) refresh token opaque hashé plutôt que JWT (révocable,
  rotatif) ; (3) filtre JWT sans hit DB (identité reconstruite des claims — TTL court compense) ;
  (4) endpoint utilitaire `/api/auth/me` ajouté (pratique, testable).
- **Écarts J4 assumés** : (1) état en signals+services, pas NgRx (ADR-0002) ; (2) `chart.js`/
  `ng2-charts`/`@stomp/stompjs` **différés** (S4/S2 — ng2-charts@10 exige Angular 21, incompatible) ;
  (3) CORS évité via proxy Angular dev plutôt que config Spring ; (4) refresh silencieux sans
  single-flight (rafraîchissements concurrents possibles — à durcir si besoin).
- **Écarts J5 assumés** : (1) démarrage FastAPI résilient à une base absente (readiness le signale,
  ne bloque pas la CI unitaire) ; (2) CI frontend en `npm run build` (AOT) plutôt que lint+Karma
  (ajoutés plus tard) ; (3) DSN asyncpg dérivé de `database_url` en retirant `+asyncpg` ; (4) warning
  Pydantic `class Config` (fichier §15) laissé tel quel — à migrer en `SettingsConfigDict` en S3.
- **Écarts S2-J1 assumés** : (1) entité JPA nommée `ImportJob` (`import` est un mot-clé Java) ;
  `Ticket` non mappée au J1 (table créée, entité au J2) ; (2) détection type/encodage maison
  (magic bytes + BOM/UTF-8) plutôt que Tika — dépendance en moins, suffisant pour CSV/XLSX/JSON/TXT ;
  (3) XLSX via `excel-streaming-reader` (pjfanning) pour le streaming sans OOM plutôt que POI
  standard ; (4) `GlobalExceptionHandler` (common) importe 2 exceptions du module `imports` —
  couplage mineur assumé pour centraliser le mapping ProblemDetail.
- **Écarts S2-J2 (backend) assumés** : (1) `Ticket.import_id`/`merged_into_id` mappés en `Long`
  (pas d'associations JPA) — couplage inter-modules évité ; (2) fichier stocké sur disque
  (`ImportStorage`) — prévoir un volume Docker en prod pour la persistance ; (3) inserts par lots
  mais **pas de vrai batch JDBC** (id en IDENTITY l'empêche — passer en SEQUENCE si besoin de perf) ;
  (4) `column_mapping` mappé via `@JdbcTypeCode(SqlTypes.JSON)` sur la colonne jsonb existante.
- **Correctif skeleton** : 3 erreurs ruff (F401 ×2, F541) corrigées dans `llm.py`/`triage.py`
  pour garder la CI ai-service verte — le `settings` importé reviendra en S3.
- **Alignement mots de passe dev** : tout `change-me` (Postgres, RabbitMQ, défauts code, `.env`,
  rapport) remplacé par `firas` à la demande de firas ; JWT secret laissé tel quel (clé HS256 ≥32
  octets, pas un mot de passe). Sync des bases faite en runtime (`ALTER USER` + `rabbitmqctl
  change_password`) car `.env` n'agit qu'à la création du volume ; recréation ai-service via
  `up --force-recreate` (un `restart` ne relit pas l'environnement).
- **Écarts S2-J4 assumés** : (1) **webhook hors JWT** (`permitAll` + auth applicative clé API + HMAC) —
  un système externe ne fait pas le flux login ; (2) corps reçu en `byte[]` (pas `@RequestBody DTO`)
  pour que le HMAC porte sur les octets exacts signés ; (3) clé/secret webhook **globaux** en dev
  (`app.webhook.*`) — prod : une paire par intégration en base ; (4) rate limit **en mémoire** par clé
  API (`ConcurrentHashMap`+Bucket4j) — prod multi-instance : Redis/Hazelcast ; (5) `bucket4j_jdk17-core`
  8.19 (la ligne `bucket4j-core` s'arrête à 8.10) ; (6) recherche liste = `LIKE` insensible casse sur
  subject/body (index GIN full-text reporté S4) ; (7) tri **whitelisté** côté service (le param `sort`
  vient du client) ; (8) `PageResponse` maison plutôt que sérialiser `PageImpl` (JSON instable + warning
  Boot 3) ; (9) filtres category/priority/sentiment différés S3 (table `analyses` absente) ; (10) route
  `/tickets` ouverte à tout rôle authentifié (AGENT+ selon §7).
- **Écarts S2-J5 assumés** : (1) test set **synthétique** (pas d'annotateurs humains) — substitut
  honnête = génération conditionnée + **filtre d'accord** (2e LLM à l'aveugle) ; documenté README ;
  (2) filtre d'accord sur `category`+`sentiment` (objectifs), priorité conditionnée + accord *reporté*
  non bloquant (subjective) ; (3) **méthodo dans `eval/README.md`, pas d'ADR** (0003 réservé au
  fine-tuning vs baseline par ADR-0002) ; (4) générateur **stdlib-only** (urllib, zéro dépendance)
  appelant les API compatibles OpenAI (Groq/Gemini/OpenRouter) — litellm/pydantic abandonnés car
  leurs extensions Rust ne compilent pas sous le Python récent de firas ; (5) `test.jsonl`
  **désormais versionné** (exception .gitignore) car référence CI ; `train.jsonl` reste ignoré ;
  (6) génération lancée **par firas** (réseau + clés API hors sandbox) — code livré, non exécuté ici.
- **Écarts S3-J1 assumés** : (1) détection langue = **heuristique** (stopwords + diacritiques), pas de
  modèle lid (sur-ingénierie pour un binaire FR/EN ; 98 % suffit) — porte de sortie si multilingue ;
  (2) baselines dans `eval/` (outil d'éval), le vrai module pipeline `app/pipeline/triage.py` reste
  câblé en S3-J3 ; `baselines.py` importe `app.nlp.language` (sys.path) et réutilise la passerelle LLM
  de `generate_dataset` (DRY) ; (3) **partie ML exécutée en sandbox** (sklearn 1.7.2 dispo, datasets sur
  le mount) — résultats réels ; la colonne **LLM zero-shot** reste à lancer par firas (tokens) ;
  (4) `priority` macro-F1 0,40 ≈ bruit : signal peu présent dans le texte — à documenter dans ADR-0003
  (peut-être dériver la priorité par règles plutôt que l'apprendre) ; (5) rapport Markdown versionné
  dans `eval/results/` (référence, rejouable).
- **Écarts S3-J2 assumés** : (1) **multi-têtes maison** (encodeur partagé + 3 `nn.Linear`) plutôt que
  3 modèles séparés — 1 seul encodeur, 1 seul ONNX, représentation partagée ; (2) **boucle PyTorch
  explicite** plutôt que `Trainer` HF — plus transparent pour le multi-tête, évite les frictions de
  gestion de labels ; (3) entraînement **sur Colab** (pas de GPU en sandbox) — notebook livré, exécuté
  par firas ; (4) nouveau dossier **`ml/`** (entraînement offline) distinct de `eval/` (évaluation) et
  du runtime `ai-service/` ; artefacts dans `ml/artifacts/` (gitignoré) ; (5) pooling = token `<s>`
  (CLS) de `last_hidden_state`, dropout 0.1 ; export ONNX opset 14 avec axes dynamiques + vérif parité.
- **Écarts S3-J3 assumés** : (1) **priorité par règles** (ADR-0003), pas de tête priorité du modèle (non
  apprenable) ; (2) modèle **monté en volume** (`./ml/artifacts:/models:ro`) plutôt que copié dans l'image
  — itération sans rebuild ; prod : intégrer au build ou registre de modèles ; (3) chargement paresseux &
  **résilient** : artefact absent → `classify()` None → escalade LLM systématique (le service démarre
  toujours, CI incluse sans modèle) ; (4) **une escalade LLM couvre les 2 têtes** incertaines (coût/latence) ;
  (5) `analyses` **écrite par FastAPI** (asyncpg) mais **table créée par Flyway côté Spring** (V3) — pas
  d'entité JPA au J3 (validate ignore les tables non mappées ; entité en S4 pour le dashboard) ;
  (6) confiance rapportée = min des softmax locaux utilisés, 0.5 si tout escaladé (le LLM ne donne pas de
  proba calibrée) ; (7) slug OpenRouter `:free` retiré → remplacé dans la chaîne LiteLLM.
- **Écarts S3-J4 assumés** : (1) **e5-base (768)** conforme au §4 ; e5-small (384) serait plus rapide CPU si
  besoin (changer aussi la dim de la colonne) ; (2) vecteurs passés à pgvector en **littéral `::vector`**
  (pas de dép `pgvector` python) — robuste, un cast SQL ; (3) similarité = cosinus pgvector `<=>` (index HNSW,
  `vector_cosine_ops`) ; (4) **KeyBERT réutilise l'embedder e5** (un seul modèle en mémoire) — préfixe e5 non
  appliqué aux candidats, acceptable ; (5) endpoints `/similar` + `/embeddings/backfill` **non authentifiés**
  (service interne, appelé par Spring ; à protéger si exposé) ; (6) `embeddings` écrite par FastAPI, table
  Flyway (V4), pas d'entité JPA au J4 ; (7) 1er embedding télécharge e5 (~1 Go) → volume `hf-cache` pour
  persister ; (8) règle de doublon = suggestion (`is_duplicate`), la **fusion** effective est un endpoint
  Spring (S4-J4) ; (9) **correctif rappel HNSW** : corpus 10k avec doublons exacts (templates du
  `generate_sample_csv`) → l'`ef_search` par défaut ratait des voisins (paraphrase à 0.98 derrière des
  tickets à 0.93). Fix : `SET` session `hnsw.ef_search=400` (config `hnsw_ef_search`) + sous-requête native
  (le `SET LOCAL` en transaction asyncpg ne prenait pas) avant chaque recherche.
- **Correctifs CI S3-J4** : (a) **ruff non déterministe** (aucune config → défauts variables local/CI :
  I001/BLE001/RUF100/RUF006/RUF002 en CI, invisibles en local) → ajout `ai-service/ruff.toml` explicite
  (`select=[E4,E7,E9,F,I,BLE,RUF]`) + fixes (noqa BLE001 sur except intentionnels, ref tâche `create_task`
  RUF006, `×`→`/`) ; lint désormais local==CI. (b) **backend : Testcontainers `postgres:16-alpine` n'a pas
  pgvector** → V4 `CREATE EXTENSION vector` échouait (25 erreurs) → images de test passées à
  `pgvector/pgvector:pg16` via `DockerImageName.asCompatibleSubstituteFor("postgres")` (5 IT) ; bonus :
  parité base test/prod. (c) `test_triage_router` stub `keywords.extract` (évite téléchargement e5 en CI).
- **Écarts S3-J5 assumés** : (1) harness lancé **dans le conteneur** (`docker compose exec`) car il a
  besoin du modèle ONNX + litellm + clés — évite aussi le litellm/Rust sous Windows ; mount `./eval:/eval` ;
  (2) macro-F1 calculé en **stdlib** (le conteneur n'a pas sklearn) ; (3) local + LLM calculés **une fois**,
  le balayage de seuil est simulé à partir (coût borné à ~300 appels LLM) ; (4) **CI eval = garde-fou
  d'intégrité** du test gelé (stdlib) et non F1-regression (nécessiterait un registre de modèles versionné —
  reporté) ; (5) **Langfuse optionnel/résilient** (callbacks litellm activés seulement si clés ; service
  self-host commenté dans compose) ; (6) ADR-0004 **accepté** : seuil **0.50** (monter le seuil double
  l'escalade pour +0,03 sentiment et dégrade la catégorie) ; passerelle LLM en multi-clés Groq + 8b.
- **Écarts S4-J1 assumés** : (1) **vues non matérialisées** + cache applicatif 60 s (fraîcheur préservée ;
  MATERIALIZED VIEW = porte de sortie si le volume explose) ; (2) **JdbcTemplate** sur les vues plutôt que
  JPA (agrégats read-only sans identité ; `ddl-auto=validate` n'a pas à connaître les vues) ; (3) `/trends`
  renvoie **toutes les séries en un appel** (évite 3 allers-retours au dashboard J2) ; (4) nom de colonne
  d'agrégat **whitelisté en dur** dans le service (jamais d'entrée utilisateur dans le SQL) ; (5) `/alerts`
  expose le contrat mais renvoie `[]` (table `alerts` + détecteurs = S7) ; (6) cache **en mémoire par
  instance** (Redis si multi-instance) ; (7) dashboard réservé **MANAGER+** (rapport §7).
- **Écarts S4-J2 assumés** : (1) **Chart.js brut + wrapper maison** (~40 lignes) plutôt que `ng2-charts`
  (incompatible Angular 18) — zéro dépendance Angular fragile, cycle de vie maîtrisé ; (2) heatmap horaire
  rendue en **barres à opacité variable** (Chart.js n'a pas de type heatmap natif ; le plugin matrix serait
  une dép de plus pour peu de valeur) ; (3) `/alerts` non encore consommé par l'UI (renvoie `[]` jusqu'en S7) ;
  (4) **repli `roleGuard` → `/tickets`** (le dashboard devenant MANAGER+, y rediriger un AGENT bouclait) ;
  (5) 2 appels HTTP au chargement (kpis + trends) — acceptable, le cache backend rend la réponse ~5 ms.
- **Écarts S4-J3 assumés** : (1) **SQL natif** (JdbcTemplate) pour la recherche au lieu des JPA
  Specifications — Hibernate ne mappe pas `tsvector` et Criteria n'exprime pas `ts_rank` ;
  `TicketSpecifications` vidée (trace de la décision), `JpaSpecificationExecutor` conservé sur le repo ;
  (2) **colonne générée** plutôt qu'index d'expression (interrogeable directement + réutilisée par
  `ts_rank` sans recalcul) ; (3) **pas d'`unaccent`** dans la colonne générée (dictionnaire modifiable →
  non IMMUTABLE) — accents gérés par la config `french` ; l'index trigram sert de fallback flou ;
  (4) config linguistique choisie **par ligne** via `CASE` sur `language` (stemming correct FR *et* EN
  dans une seule colonne) — un ticket sans langue tombe en `french` (marché francophone) ;
  (5) `websearch_to_tsquery` (et non `to_tsquery`) : ne lève jamais d'erreur sur une saisie libre ;
  (6) tri forcé sur la **pertinence** dès que `q` est présent (le tri colonne reprend sinon) ;
  (7) recherche non encore appliquée aux tickets **sans analyse** pour les filtres category/priority/
  sentiment (LEFT JOIN → un ticket non analysé est exclu si l'un de ces filtres est actif, ce qui est
  le comportement attendu).
- **Écarts S4-J4 assumés** : (1) les similaires sont obtenus via **appel HTTP à FastAPI `/similar`**
  (et non en refaisant la requête pgvector côté Spring) — une seule implémentation de la règle de
  doublon, conforme §6 ; contrepartie : dépendance réseau, donc **dégradation en liste vide** si l'IA
  est indisponible (la fiche reste utilisable) ; (2) la correction **écrase** l'analyse courante *et*
  trace l'annotation — l'historique vit dans `annotations`, `analyses` ne garde que l'état courant ;
  (3) `annotations` créée par Flyway mais **écrite via JdbcTemplate** (pas d'entité JPA, cohérent avec
  `analyses`/vues) ; (4) fusion = `merged_into_id` + statut MERGED, **sans** transfert de contenu ni
  ré-indexation (suffisant pour dédupliquer la file ; une vraie fusion métier serait un choix produit) ;
  (5) pas de RBAC spécifique sur la correction (tout AGENT+ peut corriger — c'est le principe de la
  boucle human-in-the-loop) ; (6) `POST /{id}/annotations` renvoie la **fiche complète** (évite un
  aller-retour côté UI) ; (7) brouillon de réponse RAG absent du DTO (arrive en S5).
- **Écarts S4-J5 assumés** : (1) **broker STOMP simple en mémoire** (pas de relais RabbitMQ) — suffisant
  en mono-instance ; en multi-instance il faudrait le relais pour que tous les nœuds diffusent ;
  (2) **`/ws` en permitAll** : les messages poussés ne sont que des *signaux* (id, sujet, labels), le
  client recharge via l'API REST protégée → pas de duplication du RBAC dans le canal temps réel ; durcir
  avec un token STOMP si des données sensibles y passaient ; (3) le **proxy Angular dev ne relaie pas le
  WebSocket** → le service cible directement `localhost:8080` quand le front tourne sur 4200 ;
  (4) `@stomp/stompjs` en **WebSocket natif** (SockJS écarté : plus de dépendances pour un fallback
  inutile sur navigateurs modernes) ; (5) diffusion **best-effort** des deux côtés (Spring et FastAPI) :
  une notification perdue n'annule jamais l'opération métier ; (6) `/topic/alerts` déclaré mais pas encore
  alimenté (détecteurs d'anomalies = S7) ; (7) polish UI **léger** (badge live, bandeau, chips) — pas de
  refonte de thème, arbitrage assumé en faveur de l'architecture et de l'évaluation.

- **Écarts refonte d'interface assumés** : (1) **Angular Material ni remplacé ni gardé tel quel** —
  conservé pour le comportement (select/menu/dialog/snack-bar/tooltip : ARIA, overlay, piège de focus),
  remplacé par des primitives maison partout où il n'était que présentation ; alternative « tout
  réécrire » écartée (risque de régression a11y disproportionné) ; (2) **tokens en custom properties
  CSS** et non en variables Sass — obligatoire pour basculer le thème à l'exécution sans dupliquer la
  feuille de style ; (3) **ripple Material désactivé** (~200 ms de latence perçue par clic sur poste de
  travail ; le retour vient de `:active`) ; (4) **filtres de la liste écrits dans l'URL**
  (`replaceUrl: true`, pas d'entrée d'historique) — une recherche devient partageable ; (5) **colonnes
  d'analyse conditionnelles** dans la liste : `TicketSummary` reçoit `priority/category/sentiment` en
  **optionnels**, les colonnes n'apparaissent que si l'API les fournit — décidé une seule fois au premier
  chargement pour éviter des colonnes qui apparaissent en cours de navigation ; **aucun changement d'API**
  (l'activer côté backend = ajouter 3 colonnes au SELECT de `TicketSearchRepository`, déjà jointes, et
  3 champs à `TicketSummaryResponse`) ; (6) `avgConfidence` était affiché brut (`0.87`), désormais en
  pourcentage — la vue V5 renvoie bien un ratio 0-1 (`AVG(a.confidence)`), vérifié ; (7) **route `**`
  déplacée dans la coquille** (page 404 dédiée) au lieu d'une redirection silencieuse vers l'accueil ;
  (8) redirection post-connexion **calculée selon le rôle** (un AGENT allait sur `/dashboard` puis était
  renvoyé par le `roleGuard` — un clignotement à chaque connexion) ; (9) build complet non exécuté dans
  le sandbox (I/O du mount Windows trop lent) : vérification par `tsc` + **`ngc --noEmit` avec
  `strictTemplates`** + compilation Sass de toutes les feuilles ; (10) budgets `angular.json` relevés
  (`anyComponentStyle` 2/4 ko → 10/20 ko) — les styles de composant les plus lourds font ~6 ko compressés ;
  (11) **exception à « backend non touché »** : à la demande de firas, la vue liste retourne désormais
  priorité/catégorie/humeur. Contrat *étendu*, jamais cassé — 3 champs ajoutés en fin de record, aucun
  champ existant modifié ni supprimé, donc aucun client existant n'est impacté. Le tri sur ces colonnes
  reste **volontairement absent** : `SORTABLE` ne contient que des colonnes de `tickets`, et trier la
  priorité par ordre alphabétique (HIGH, LOW, MEDIUM) serait faux — il faudrait un `CASE` d'ordre métier
  dans la liste blanche, à faire si le besoin se présente.
- **Écarts passe 2 (i18n) assumés** : (1) **i18n runtime maison** plutôt qu'`@angular/localize` —
  exigence de bascule instantanée incompatible avec un bundle par langue ; contrepartie : les deux
  dictionnaires (~37 ko de source) sont dans le bundle ; (2) **pipe `t` impur** — nécessaire pour que
  la bascule se propage ; coût négligeable car tous les composants sont en `OnPush` ; (3) `RelativeTimePipe`
  et `AbsoluteTimePipe` passés en `pure: false` (leur rendu dépend de l'heure **et** de la langue) ;
  (4) pluriel géré par une fonction `plural(n, singular, plural)` — suffisant pour FR/EN, une vraie
  ICU MessageFormat serait nécessaire pour le russe ou l'arabe ; (5) messages d'erreur stockés en
  **clé** et non en texte dans les signals, pour qu'ils suivent un changement de langue après coup ;
  (6) seuils de la section « À retenir » (25 % d'urgences, 30 % de négatif) = repères de bon sens,
  **pas** des valeurs apprises — à calibrer avec un vrai historique.
- **Correctif refonte (menu du compte invisible)** : `_material.scss` masquait `.mat-mdc-focus-indicator`
  en même temps que `.mat-ripple`, pour couper l'ondulation. Erreur de diagnostic : contrairement à
  `.mat-ripple` (posée uniquement sur des `<div>` dédiées), `mat-mdc-focus-indicator` est inscrite dans le
  `classAttribute` de **l'hôte** de `mat-menu-item`, `mat-chip-remove` et `mat-tab-link` — les entrées du
  menu du compte (thème, déconnexion) étaient donc en `display: none`. Vérifié par `grep classAttribute`
  dans `@angular/material/fesm2022/*.mjs` (méthode : lire la source de la lib plutôt que deviner).
  **Règle retenue** : ne jamais poser `display: none` sur une classe Material sans avoir vérifié si c'est
  un *marqueur d'hôte* ou un *élément de décoration*. Le marqueur n'a de toute façon aucun effet visuel
  tant que le mixin `strong-focus-indicators()` n'est pas activé. Padding/gap des entrées de menu repris
  au passage (les variables Material sont calées sur 48 px, nos entrées font 34 px).

- **Écarts S5-J1 assumés** : (1) **`heading` et `model` ajoutés** à `kb_documents` par rapport au §4 —
  le premier porte les citations (S5-J3/J4), le second la traçabilité d'un changement de modèle ;
  (2) **remplacement transactionnel** plutôt qu'UPSERT par fragment (un document réécrit peut avoir
  moins de fragments qu'avant) ; (3) **PyMuPDF** et non pypdf (ordre de lecture visuel) ; docx/OCR
  restent en S7 conformément au §5.4 ; (4) **Spring lit `kb_documents` en direct** (agrégation SQL,
  aucun modèle requis) mais **écrit via FastAPI** — même frontière qu'`analyses`/`embeddings` ;
  (5) `KbClient` **ne dégrade pas en silence** (contrairement à `SimilarTicketClient`) car un import
  raté doit être visible ; (6) recherche **purement vectorielle** au J1 : l'hybride BM25+RRF+reranking
  est le sujet du J2, et la mesure d'aujourd'hui servira de point de comparaison chiffré ;
  (7) `/kb/search` ouvert **AGENT+** (consultation, ne modifie rien), écriture réservée ADMIN ;
  (8) corpus de démo **écrit à la main**, aligné sur le vocabulaire des tickets synthétiques — une KB
  qui parle d'un autre produit que les tickets ne prouverait rien.
- **Écarts S5-J2 assumés** : (1) **BM25 en mémoire** (`rank_bm25`) et non l'index GIN de PostgreSQL —
  justifié à l'échelle d'une FAQ (index reconstruit en ms) ; porte de sortie connue au-delà de
  ~50 000 fragments, le GIN existe déjà pour les tickets depuis S4-J3 ; (2) **RRF plutôt qu'une
  somme pondérée** — pas de normalisation d'échelles à maintenir, pas d'α à recalibrer ;
  (3) cross-encodeur **`mmarco-mMiniLMv2`** (~470 Mo) et non `bge-reranker-base` (~1,1 Go) : e5
  occupe déjà ~1 Go, le budget mémoire du conteneur prime sur les derniers points de précision ;
  (4) **44 paires** au lieu des 40 du rapport — couvrir les 20/20 sections valait mieux que
  s'arrêter au chiffre rond ; (5) annotation par **(source, heading)** et non par `id` — les id
  changent au ré-import ; (6) le harness **réindexe le corpus** au démarrage pour que deux
  exécutions soient comparables ; (7) mode `vector` **conservé** en production (et pas seulement
  dans l'éval) : il alimente le comparateur de l'écran d'administration, qui *montre* l'écart au
  lieu de l'affirmer ; (8) `rerank_enabled` **désactivé par défaut** après mesure (ADR-0005) : le
  reranking dégradait le MRR de 0,900 à 0,859 pour 170× la latence. Le code reste en place et la
  porte de sortie est documentée (GPU, ou corpus > quelques milliers de fragments).
- **Écarts S5-J4 assumés** : (1) **`final_content` en colonne séparée** plutôt qu'écrasement de
  `content` — le juge de S5-J5 doit noter le modèle, pas l'agent ; effet de bord bénéfique, la
  distance entre les deux devient une métrique ; (2) statut **`SENT` = « validé, bon pour envoi »** :
  la plateforme n'a **aucun canal d'envoi** (l'e-mail sortant arrive en S6-J4) — l'interface dit
  donc « Valider », pas « Envoyer ». Nommer un statut d'après une action inexistante serait un
  mensonge de plus en base ; (3) **`PATCH /api/drafts/{id}`** (ressource propre) et non
  `POST /api/tickets/{id}/draft/review` : la revue porte sur *ce* brouillon, pas sur le dernier en
  date — sinon deux agents sur la même fiche pendant qu'un troisième régénère valideraient un texte
  qu'ils n'ont pas lu ; (4) `DraftException` **duplique la forme de `KbException`** — assumé : deux
  petites exceptions au propriétaire clair valent mieux qu'une abstraction posée sur deux cas ;
  remontée dans `common/error` au **troisième** client du service IA (agent Insight, S6) ; (5) les
  citations sont **réhydratées côté Spring** (`kb_documents` en JdbcTemplate) et non redemandées à
  FastAPI : lire un fragment par son id est une requête, pas un calcul — la frontière §6 tient ;
  (6) **pas de nœud de validation humaine dans le graphe LangGraph** : la revue arrive parfois des
  jours plus tard, un checkpointer mémoire ne survivrait pas au redémarrage. Le graphe s'arrête à
  `persist`, la boucle humaine vit dans la table. `AsyncPostgresSaver` reste la porte de sortie si
  un jour un nœud doit vraiment attendre ; (7) `httpclient5` en dépendance **de test** uniquement
  (PATCH inconnu de HttpURLConnection) ; (8) génération **non testée en intégration** (dépendrait
  d'une clé d'API et d'une sortie non déterministe) — c'est la machine à états qui est couverte ;
  (9) brouillon **non poussé en WebSocket** : il est demandé par l'agent qui regarde déjà la fiche,
  il n'y a personne à prévenir ; (10) `allowSignalWrites` sur l'effet de chargement (l'effet pilote
  volontairement l'état du panneau), avec **garde anti-réponse périmée** — sans elle, naviguer vite
  entre deux tickets peut afficher le brouillon du précédent sur le suivant ; (11) pas de RBAC
  spécifique sur la revue : tout AGENT+ peut trancher, c'est le principe de la boucle
  human-in-the-loop (même choix qu'en S4-J4 pour les corrections).
- **Écarts S5-J5 assumés** : (1) grille **0-1-2** et non 1-5 (fiabilité inter-appels contre
  résolution) ; (2) **exactitude en verrou** plutôt qu'en tiers de moyenne — l'agrégation doit
  encoder la hiérarchie des défauts, pas les diluer ; (3) **abstentions exclues** du calcul et
  reportées séparément comme métrique de couverture ; (4) **juge 70b ≠ rédacteur 8b** via un
  paramètre `groq_model` ajouté à la passerelle — la chaîne de repli reste intacte, mais le modèle
  effectivement utilisé est remonté ; (5) **passages rejoués** au moment du jugement plutôt que
  stockés dans le brouillon (la recherche est déterministe à KB constante) — caveat assumé si la KB
  change entre rédaction et jugement, sans objet dans une campagne qui fait les deux d'affilée ;
  (6) `set_judge_score` **écrit en place**, seule exception à la règle d'ajout du module ; (7) la
  campagne **n'entre pas en CI** (quota externe = rouge non informatif), seules ses parties
  déterministes y entrent ; (8) **une seule note par brouillon** — la stabilité du juge n'est pas
  mesurée (il faudrait noter deux fois et comparer), c'est la première chose à ajouter si le
  protocole doit servir à des décisions plus fines ; (9) `judge_score` **non affiché dans l'UI** :
  c'est une métrique d'évaluation hors ligne, elle ne change aucune action de l'agent ; (10) ADR-0006
  laissé en statut **proposé** avec un tableau de résultats vide — il passera en *accepté* quand les
  chiffres seront là, comme ADR-0004.
- **Écarts S6-J1 assumés** : (1) **ADR numéroté 0007** alors que le rapport §16 réservait 0006 aux
  guardrails text-to-SQL — 0006 a été pris par le LLM-as-judge une semaine plus tôt ; renuméroter un
  ADR déjà référencé ferait plus de dégâts que l'écart ; (2) **3 vues créées** (`v_tickets`,
  `v_daily_volume`, `v_draft_activity`) en plus des 3 du dashboard (V5), toutes whitelistées — le
  rapport ne citait que `v_ticket_stats` et `v_category_trends`, insuffisants pour des questions
  libres ; (3) **liste noire de fonctions** (`pg_sleep`, `dblink`…) et non liste blanche : une
  liste blanche casserait les questions légitimes à chaque nouvel agrégat utile ; c'est un **filet**,
  la protection réelle est le rôle en lecture seule ; (4) **DSN Insight dérivé** de `database_url`
  en remplaçant utilisateur et mot de passe, plutôt qu'une seconde URL complète — deux URL en
  parallèle divergent toujours d'un caractère un jour de déploiement ; (5) mot de passe par
  **placeholder Flyway** plutôt qu'en dur dans la migration ; défaut `insight` en dev, à changer en
  prod ; (6) **pas de limitation de débit** par utilisateur (cent questions d'affilée consomment
  jetons et base) — à traiter au J3 avec l'interface, où le débit se mesure ; (7) `user_role` du
  contrat §6 accepté mais **ignoré** : un service interne qui se fierait à un rôle transmis dans un
  corps JSON n'aurait aucune sécurité ; (8) génération LLM **non testée en intégration** (sortie non
  déterministe, clé d'API requise) — ce sont les deux barrières qui sont couvertes, pas la qualité
  du SQL, qui est le sujet de la suite d'éval du J2.
- **Écarts S6-J2 assumés** : (1) **`chart_spec` calculé par le code** et non demandé au modèle,
  alors que le rapport §6 le présente comme une sortie de l'agent — le contrat est respecté, c'est
  le producteur qui change ; (2) **jamais de camembert** : il affirme une partition que le code ne
  peut pas vérifier ; (3) **une seule série** par graphique (`y` = première colonne numérique) —
  les séries groupées demanderaient un format plus riche, à faire si le J3 le réclame ; (4) **pas de
  checkpointer** sur ce graphe, contrairement à l'agent Résolution ; (5) l'ordre des **lignes** est
  ignoré dans la comparaison d'éval, celui des **colonnes** ne l'est pas (reporté à part) ;
  (6) questions d'éval **précises par construction** — une question ambiguë (« répartis les
  humeurs » : avec ou sans les non-analysés ?) n'a pas de réponse de référence légitime, la suite
  mesure donc la traduction de questions claires ; (7) SQL de référence écrit par la même personne
  que le prompt : biais résiduel assumé et documenté dans le rapport d'éval ; (8) **aucun test
  exécuté de mon côté** (sandbox indisponible) — vérification par relecture seule.

---

## 6. Repères rapides

- **Modèle de données** : rapport §4 (`users`, `refresh_tokens`, `imports`, `tickets`,
  `analyses`, `embeddings`, `annotations`, `kb_documents`, `draft_responses`, `alerts`,
  `agent_runs`). Index GIN full-text, HNSW sur vecteurs, composites `(status, sla_due_at)`
  et `(category, created_at)`.
- **Contrats d'API** : rapport §6 (Spring plan de contrôle / FastAPI plan de calcul).
- **Périmètre & discipline d'incréments** : §2 (cœur = F1-F5, F9-F11, formats structurés ;
  stretch S6-S7 = F6-F8, F12, doc F13). Le cahier des charges est couvert dès fin S4.
- **Risques & plans B** : §11. **Scénario de démo** : §13.
- **Vérif santé service IA** : `curl http://localhost:8001/health` → `{"status":"ok"}`.
- **Vérif santé backend** : `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`.
- **Vérif des dépendances optionnelles** : `curl http://localhost:8001/health/ready` → `database` et
  `insight_readonly` (accès text-to-SQL en lecture seule, S6-J1).

---

## 7. Piège Docker récurrent — `--build` ne recrée pas le conteneur

Constaté **deux fois de suite** en S6-J1 (paquet `sqlglot` absent, puis migration V11 jamais vue) :
`docker compose up -d --build <service>` construit bien une nouvelle image, mais **ne redémarre pas
forcément le conteneur dessus**. Symptôme : `docker compose ps` affiche un `sha256:…` brut dans la
colonne IMAGE au lieu du nom du service — le tag pointe désormais vers la nouvelle image, le
conteneur tourne encore sur l'ancienne.

- **ai-service** : `./ai-service/app` est monté en volume, donc le **code** est toujours à jour mais
  les **paquets** viennent de l'image. Un ajout dans `requirements.txt` exige une recréation.
- **backend** : aucun bind mount, le jar embarque les migrations Flyway. Une migration éditée
  n'existe pour Flyway qu'après reconstruction **et** recréation.

```powershell
docker compose up -d --build --force-recreate backend
```

**Règle de diagnostic tirée de l'épisode** : quand une trace manque (pas de ligne dans
`flyway_schema_history`, module introuvable), l'hypothèse « ça n'a jamais été exécuté » passe
**avant** « ça a été exécuté et a échoué ». `docker compose ps` avant les logs. Et **élargir la
fenêtre de log avant d'expliquer un silence** : deux fois en S6-J1, un `--tail` trop court m'a fait
conclure à un échec là où la ligne recherchée était simplement hors cadre.

### Corollaire — cache pip du Dockerfile ai-service

`--no-cache-dir` interdisait à pip de conserver les roues. Une ligne ajoutée à `requirements.txt`
invalidait la couche et re-téléchargeait **tout**, dont torch (~2 Go, tiré par
sentence-transformers) : 34 minutes puis un timeout. Le Dockerfile utilise désormais
`--mount=type=cache,target=/root/.cache/pip` (cache du démon, hors image) plus `--timeout 120
--retries 10`. Le premier build reste long, les suivants ne retéléchargent que le nouveau paquet.

**Dépannage immédiat** quand un seul petit paquet manque (pur Python) :
`docker compose exec ai-service pip install <paquet>` puis `docker compose restart ai-service` — le
paquet survit à un `restart` mais **pas** à un `up --force-recreate`.
