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
- **Prochaine étape : Semaine 2 — Jour 5** — génération du dataset synthétique (script LLM,
  500–1000 tickets FR/EN, catégories équilibrées) + **gel du test set** (300 tickets étiquetés main,
  jamais en entraînement) → `train.jsonl` / `test.jsonl` versionnés. Démo 2. Voir rapport §9 Semaine 2.

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
