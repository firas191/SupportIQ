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
- **Prochaine étape : Semaine 1 — Jour 4** — frontend Angular 18 : workspace, routing, guards,
  intercepteur JWT + refresh silencieux, layout, pages login/register, état (ADR NgRx vs signals).

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
- **Correctif skeleton** : 3 erreurs ruff (F401 ×2, F541) corrigées dans `llm.py`/`triage.py`
  pour garder la CI ai-service verte — le `settings` importé reviendra en S3.

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
