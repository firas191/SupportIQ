# SupportIQ — Plateforme Agentique d'Analyse & de Résolution des Tickets de Support

**Rapport technique & plan d'exécution — Stage de 8 semaines**
**Stack imposée :** Angular · Spring Boot · PostgreSQL · Python/FastAPI
**Extensions proposées :** LangGraph · pgvector · LiteLLM · RabbitMQ · Docker · GitHub Actions

---

## 1. Vision & positionnement

Le cahier des charges initial décrit un outil d'analyse *passive* : on importe des tickets, on les classe, on affiche des graphiques. C'est un projet de fin de module, pas un produit.

**SupportIQ transforme cet outil en copilote opérationnel pour les équipes support.** La plateforme ne se contente pas de *décrire* les tickets — elle **agit** :

- Elle **triage** automatiquement (priorité, catégorie, sentiment, langue) avec un pipeline hybride ML + LLM optimisé coût/latence.
- Elle **rédige des brouillons de réponse** fondés sur une base de connaissances interne (RAG), que l'agent humain valide ou corrige (human-in-the-loop).
- Elle **détecte les signaux faibles** : sujets émergents, pics anormaux de volume, tickets à risque de violation de SLA, doublons.
- Elle **répond en langage naturel aux questions des managers** ("quelle catégorie a explosé cette semaine ?") via un agent Text-to-SQL sécurisé.
- Elle **apprend en continu** : chaque correction humaine alimente un jeu de données de ré-entraînement (boucle d'active learning).

**Différenciateur clé pour un recruteur :** ce n'est pas "un dashboard + une API de classification". C'est un système multi-agents orchestré par **LangGraph**, avec routage intelligent de modèles, évaluation quantitative, observabilité LLM et une vraie boucle de feedback. Le genre d'architecture qu'on retrouve dans les produits SaaS de support modernes (Intercom Fin, Zendesk AI), reconstruite à coût zéro avec des LLM gratuits.

---

## 2. Périmètre fonctionnel

### 2.1 Socle (cahier des charges — conservé intégralement)

| Module | Description |
|---|---|
| Authentification | JWT (access + refresh), rôles RBAC |
| Import CSV | Upload, mapping de colonnes, prévisualisation, validation |
| Analyse IA | Priorité, catégorie, sentiment, mots-clés, score de confiance |
| Dashboard | KPIs, graphiques, évolution temporelle |
| Recherche | Recherche plein texte + filtres combinés |
| Détail ticket | Vue complète d'un ticket + historique d'analyse |

### 2.2 Extensions proposées (les différenciateurs)

| # | Fonctionnalité | Valeur | Techno clé |
|---|---|---|---|
| F1 | **Pipeline hybride ML + LLM avec routage par confiance** | 90 % des tickets classés par un modèle fine-tuné local (rapide, gratuit) ; seuls les cas ambigus (< seuil de confiance) montent vers un LLM. Argument coût/latence = discours senior. | XLM-RoBERTa fine-tuné + LiteLLM |
| F2 | **Recherche sémantique & détection de doublons** | Embeddings stockés dans PostgreSQL (pgvector). "Tickets similaires" sur chaque fiche, fusion de doublons suggérée. | pgvector, multilingual-e5 |
| F3 | **Agent de Résolution (RAG)** | Brouillon de réponse généré à partir d'une base de connaissances (FAQ, docs produit) + tickets résolus similaires. Validation humaine obligatoire. | LangGraph, RAG, reranking |
| F4 | **Agent Insight (Text-to-SQL)** | Chat manager : questions en langage naturel → SQL contrôlé (whitelist de vues) → réponse + graphique. | LangGraph, SQL guardrails |
| F5 | **Agent Digest** | Rapport hebdomadaire auto-généré (PDF/email) : tendances, top irritants, tickets critiques ouverts. | LangGraph, génération structurée |
| F6 | **Détection de sujets émergents** | Clustering non supervisé des tickets récents → "nouveau problème détecté : échec paiement mobile (+340 % en 48 h)". | BERTopic / HDBSCAN |
| F7 | **Détection d'anomalies de volume** | Alerte si le volume d'une catégorie dévie statistiquement de la saisonnalité. | STL + z-score |
| F8 | **Prédiction de risque SLA** | Score de probabilité qu'un ticket dépasse son délai cible, pour prioriser la file. | Gradient boosting (features ticket + historique) |
| F9 | **Ingestion temps réel** | Au-delà du CSV : endpoint webhook REST + connecteur email (IMAP) → file RabbitMQ → analyse asynchrone. | RabbitMQ, Spring AMQP |
| F10 | **Human-in-the-loop & active learning** | L'agent humain corrige une classification → la correction est versionnée et exportable comme dataset de ré-entraînement. | Table `annotations`, export JSONL |
| F11 | **Observabilité LLM & évaluation** | Traces de chaque appel LLM (latence, tokens, coût, prompt) + harness d'évaluation : précision/rappel par classe sur un jeu étiqueté, LLM-as-judge pour la qualité des brouillons. | Langfuse (self-hosted), pytest-eval |
| F12 | **Temps réel UI** | Nouveaux tickets et alertes poussés au dashboard sans refresh. | WebSocket (STOMP) |
| F13 | **Ingestion universelle multi-format** | Un ticket peut arriver en CSV, XLSX, JSON, TXT, PDF, DOCX ou email : les formats structurés passent par le mapping de colonnes ; les documents non structurés par un pipeline extraction → structuration LLM (1 document → N tickets, confiance par champ) avec écran de validation obligatoire. | PyMuPDF, python-docx, Tesseract OCR, sortie structurée Pydantic |

> **Discipline de périmètre :** F1–F5, F9–F11 et les formats structurés de F13 (CSV/XLSX/JSON/TXT) sont le cœur. F6–F8, F12 et la partie documentaire de F13 (PDF/DOCX/OCR, structuration LLM) sont des modules "stretch" planifiés en semaines 6–7 avec un plan B explicite (voir §11 et §13). C'est aussi un signal senior : savoir découper en incréments livrables.

---

## 3. Architecture technique

```
                        ┌──────────────────────────────┐
                        │        Angular 18 SPA        │
                        │  Dashboard · Tickets · Chat  │
                        │  NgRx · Chart.js · STOMP WS  │
                        └──────────────┬───────────────┘
                                       │ HTTPS / WSS
                        ┌──────────────▼───────────────┐
                        │     Spring Boot 3 (Java 21)  │
                        │  API REST · JWT/RBAC · WS    │
                        │ Import multi-fmt·Orchestration│
                        └───────┬──────────────┬───────┘
                     JDBC       │              │  AMQP (async)
              ┌─────────────────▼──┐   ┌───────▼────────┐
              │  PostgreSQL 16     │   │   RabbitMQ     │
              │  + pgvector        │   │ file d'analyse │
              │  données + vecteurs│   └───────┬────────┘
              └─────────────────▲──┘           │ consume
                                │       ┌──────▼─────────────────┐
                                └───────┤  Service IA — FastAPI  │
                                        │  Pipeline hybride NLP  │
                                        │  Agents LangGraph      │
                                        │  LiteLLM Gateway ──────┼──► Groq / Gemini /
                                        │  Langfuse (traces)     │    OpenRouter / Ollama
                                        └────────────────────────┘
```

### Choix d'architecture justifiés (arguments d'entretien)

1. **Analyse asynchrone via RabbitMQ.** L'import d'un CSV de 10 000 tickets ne doit pas bloquer une requête HTTP. Spring publie des messages `ticket.created`, FastAPI consomme, analyse, puis publie `ticket.analyzed`. Découplage, résilience (retry + DLQ), scalabilité horizontale du worker IA.
2. **pgvector plutôt qu'un vector store séparé.** Un seul système de stockage = transactions ACID entre données métier et embeddings, pas d'infra supplémentaire, jointures SQL directes ("tickets similaires **de la même catégorie**"). À cette échelle, FAISS/Qdrant serait de la sur-ingénierie.
3. **Routage de modèles par confiance (F1).** Le réflexe junior est "tout envoyer au LLM". Le réflexe senior est un classifieur local fine-tuné (~30 ms, 0 $) pour les cas standards, LLM uniquement en fallback. On mesure et on affiche le taux d'escalade.
4. **LiteLLM comme passerelle unique.** Une interface OpenAI-compatible devant Groq, Gemini, OpenRouter et Ollama : failover automatique quand un quota gratuit est épuisé, aucun vendor lock-in, un seul point d'instrumentation.
5. **Spring Boot = plan de contrôle, FastAPI = plan de calcul.** Sécurité, transactions, règles métier côté JVM ; NLP, agents et GPU-éventuel côté Python. Frontière claire, contrat OpenAPI versionné entre les deux.

---

## 4. Modèle de données (PostgreSQL 16 + pgvector)

```
users(id, email, password_hash, full_name, role[ADMIN|MANAGER|AGENT], created_at)
refresh_tokens(id, user_id, token_hash, expires_at, revoked)

imports(id, filename, file_type[CSV|XLSX|JSON|TXT|PDF|DOCX|EML], uploaded_by, row_count,
        status[PENDING|EXTRACTING|AWAITING_VALIDATION|PROCESSING|DONE|FAILED],
        column_mapping jsonb, extraction_meta jsonb, created_at)

tickets(id, external_ref, import_id, source[FILE|WEBHOOK|EMAIL|MANUAL], customer_email,
        subject, body, language[fr|en], created_at, sla_due_at,
        status[NEW|ANALYZED|IN_PROGRESS|RESOLVED|MERGED], merged_into_id)

analyses(id, ticket_id, priority[LOW|MEDIUM|HIGH], category, sentiment[NEG|NEU|POS],
         keywords text[], confidence numeric, model_used, latency_ms,
         escalated_to_llm bool, created_at)

embeddings(ticket_id PK, vector vector(768), model, created_at)

annotations(id, ticket_id, field[priority|category|sentiment], predicted, corrected,
            corrected_by, created_at)          -- boucle active learning

kb_documents(id, title, source, chunk_index, content, vector vector(768), updated_at)

draft_responses(id, ticket_id, content, citations jsonb, status[PROPOSED|EDITED|SENT|REJECTED],
                judge_score numeric, reviewed_by, created_at)

alerts(id, type[VOLUME_ANOMALY|EMERGING_TOPIC|SLA_RISK], payload jsonb, severity,
       acknowledged_by, created_at)

agent_runs(id, agent[TRIAGE|RESOLUTION|INSIGHT|DIGEST], input jsonb, output jsonb,
           trace_id, tokens_in, tokens_out, latency_ms, status, created_at)
```

Index clés : `GIN` sur `tickets(subject, body)` (full-text FR/EN), `HNSW` sur les colonnes `vector`, index composites sur `(status, sla_due_at)` et `(category, created_at)` pour le dashboard. Migrations versionnées avec **Flyway**.

---

## 5. Service IA — conception détaillée

### 5.1 Pipeline de triage hybride (F1)

```
texte brut
  → détection de langue (lingua / fastText) → sélection des prompts et analyseurs FR/EN
  → classifieur local XLM-RoBERTa fine-tuné (priorité, catégorie, sentiment)
        │ confiance ≥ 0.80  → résultat direct (~30 ms)
        │ confiance < 0.80  → escalade LLM (few-shot structuré, sortie JSON validée Pydantic)
  → extraction de mots-clés (KeyBERT multilingue)
  → embedding (multilingual-e5-base, 768d) → pgvector
```

- **Dataset de fine-tuning :** dataset public de tickets clients (ex. corpus multilingue de e-commerce) + 500–1 000 tickets synthétiques générés par LLM en FR/EN (styles variés : formel, énervé, télégraphique), relus manuellement. Le rapport documente précision/rappel par classe **avant/après** fine-tuning — chiffre concret pour le CV.
- **Contrat de sortie strict :** toute réponse LLM est validée par un schéma Pydantic ; en cas d'échec de parsing → retry avec message d'erreur injecté → fallback règles. Zéro JSON cassé en base.

### 5.2 Couche agentique — LangGraph

Quatre agents, chacun un graphe explicite avec état typé, checkpoints et garde-fous :

**Agent Triage** — orchestration du pipeline 5.1 + décision d'enrichissement (recherche de tickets similaires, liaison de doublon si similarité cosinus > 0.92 sur la même catégorie).

**Agent Résolution (RAG)** —
```
retrieve (kb + tickets résolus similaires, top-k hybride BM25 + vecteurs)
  → rerank (cross-encoder) → generate (brouillon cité, ton configurable)
  → self-check (le brouillon répond-il à la question ? citations valides ?)
  → si échec self-check : re-génération (max 2) sinon flag "faible confiance"
```
Chaque brouillon porte ses **citations** (chunks sources cliquables dans l'UI). Envoi impossible sans validation humaine — argument de responsible AI à mettre en avant.

**Agent Insight (Text-to-SQL)** — question NL → génération SQL **restreinte à des vues en lecture seule** (`v_ticket_stats`, `v_category_trends`…), validation AST (SELECT uniquement, pas de sous-requête vers les tables brutes), exécution avec timeout, réponse en langage naturel + spécification de graphique renvoyée au frontend. Les guardrails SQL sont un point d'entretien en or.

**Agent Digest** — chaque lundi (scheduler) : agrégats de la semaine → synthèse structurée (tendances, top 5 irritants, tickets critiques ouverts, anomalies) → rendu Markdown/PDF + email aux managers.

### 5.3 Observabilité & évaluation (F11)

- **Langfuse self-hosted** : chaque appel LLM tracé (prompt, tokens, latence, modèle, coût théorique), regroupé par `agent_run`.
- **Harness d'évaluation** (exécuté en CI) :
  - Classification : precision/recall/F1 par classe sur un set de test gelé (~300 tickets étiquetés main).
  - RAG : recall@k du retrieval sur des paires question/document annotées ; LLM-as-judge (grille : exactitude, complétude, ton) sur 50 brouillons.
  - Text-to-SQL : suite de 30 questions avec SQL de référence, comparaison des résultats d'exécution.
- **Tableau "Qualité IA" dans l'app** : taux d'escalade LLM, taux de correction humaine par classe, latence P50/P95 — l'IA qui se mesure elle-même.

### 5.4 Couche d'ingestion universelle (F13)

Un seul principe : **quel que soit le format d'entrée, tout converge vers la même chaîne `ticket.created` → triage**. Deux chemins selon la nature du fichier :

```
fichier → détection MIME + sniffing de contenu
  │
  ├─ STRUCTURÉ (CSV, XLSX, JSON, TXT tabulaire)
  │    → parsing streaming (OpenCSV / Apache POI côté Spring)
  │    → mapping de colonnes réutilisable → prévisualisation → insertion
  │
  └─ NON STRUCTURÉ (PDF, DOCX, TXT libre, EML, PDF scanné)
       → extraction texte (PyMuPDF / python-docx ; ratio de texte extractible
         trop faible ? → fallback OCR Tesseract)
       → structuration LLM : schéma Pydantic TicketBatch
         (1 document → N tickets : sujet, corps, email client, date,
          confiance PAR CHAMP)
       → écran de validation : l'utilisateur corrige/confirme chaque
         ticket extrait AVANT toute insertion en base
```

Décisions à défendre en entretien :
- **Jamais d'insertion silencieuse depuis un LLM.** L'extraction structurée est probabiliste : le statut `AWAITING_VALIDATION` impose une revue humaine, les champs à faible confiance sont surlignés. Même philosophie que les brouillons RAG.
- **La structuration LLM réutilise l'existant** : passerelle LiteLLM, validation Pydantic avec retry, traces Langfuse — zéro nouveau composant d'infrastructure.
- **L'OCR est un fallback mesuré, pas un défaut** : on tente d'abord l'extraction native (plus fiable et gratuite) ; le ratio de texte extractible décide.
- **Le connecteur email (S7) devient un simple producteur de plus** branché sur ce même pipeline non structuré — l'architecture paie.

---

## 6. Contrats d'API (extraits principaux)

### Spring Boot (plan de contrôle)
```
POST   /api/auth/login | /refresh | /logout
POST   /api/imports                      (multipart multi-format → job async)
GET    /api/imports/{id}/extracted       (tickets extraits en attente de validation)
POST   /api/imports/{id}/confirm         (validation humaine → insertion + analyse)
GET    /api/imports/{id}                 (statut + erreurs de lignes)
GET    /api/tickets?query=&category=&priority=&sentiment=&lang=&slaRisk=&page=
GET    /api/tickets/{id}                 (ticket + analyse + similaires + brouillon)
POST   /api/tickets/{id}/annotations     (correction humaine)
POST   /api/tickets/{id}/merge           (fusion doublon)
GET    /api/dashboard/kpis | /trends | /alerts
POST   /api/webhooks/tickets             (ingestion temps réel, clé API)
WS     /ws  (topics: /topic/tickets, /topic/alerts)
```

### FastAPI (plan de calcul — consommé par Spring + RabbitMQ)
```
POST /analyze            {ticket_text, language?} → AnalysisResult
POST /extract            {file_ref, mime_type}      → TicketBatch (docs non structurés)
POST /similar            {ticket_id | text, k}    → [SimilarTicket]
POST /agents/resolution  {ticket_id}              → DraftResponse
POST /agents/insight     {question, user_role}    → {answer, sql, chart_spec}
POST /agents/digest      {week}                   → DigestReport
GET  /health | /metrics
```

---

## 7. Sécurité

- JWT access (15 min) + refresh rotatif (7 j, stocké hashé, révocable) ; BCrypt cost 12.
- RBAC : `AGENT` (tickets, annotations), `MANAGER` (+ dashboard, insight, digest), `ADMIN` (+ utilisateurs, imports, KB).
- Webhook d'ingestion : clé API + HMAC de signature + rate limiting (Bucket4j).
- Text-to-SQL : vues read-only, rôle PostgreSQL dédié `insight_ro`, validation AST, timeout 5 s.
- Anonymisation optionnelle des emails clients à l'import (RGPD-friendly, bon point de discours).
- Prompts : instructions système séparées du contenu utilisateur, tickets traités comme données non fiables (mitigation prompt injection sur le RAG).

---

## 8. DevOps & qualité

- **Docker Compose** : `angular` (nginx), `spring`, `fastapi`, `postgres+pgvector`, `rabbitmq`, `langfuse`, `ollama` (optionnel). `docker compose up` = démo complète en une commande.
- **GitHub Actions** : lint + tests unitaires (JUnit 5 / pytest / Karma) + harness d'évaluation IA + build des images sur chaque PR.
- **Qualité** : couverture cible 70 % sur les services métier Spring ; tests d'intégration Testcontainers (PostgreSQL réel) ; tests de contrat entre Spring et FastAPI (schémas OpenAPI).
- **Documentation** : README d'architecture avec diagrammes, ADRs (Architecture Decision Records — 6 à 8 décisions documentées : pourquoi pgvector, pourquoi RabbitMQ, etc.), collection Postman/Bruno, guide de déploiement.

---

## 9. Planning détaillé — 8 semaines × 5 jours

> Rythme volontairement dense. Chaque semaine se termine par un **incrément démontrable** (règle : "vendredi, on peut montrer quelque chose à l'encadrant"). Les jours listent le livrable attendu, pas juste l'activité.

### Semaine 1 — Fondations & authentification
| Jour | Travail | Livrable de fin de journée |
|---|---|---|
| J1 | Kickoff : relecture cahier des charges, validation du périmètre étendu avec l'encadrant, repo Git (monorepo `frontend/ backend/ ai-service/ infra/`), conventions (commits, branches, PR). Docker Compose initial : PostgreSQL 16 + pgvector, RabbitMQ, pgAdmin. | Environnement complet démarre en une commande |
| J2 | Spring Boot 3 : squelette, profils (dev/prod), Flyway V1 (users, refresh_tokens), entités + repositories, gestion d'erreurs globale (ProblemDetail RFC 7807). | API démarre, migrations appliquées |
| J3 | Auth complète : register/login, JWT access + refresh rotatif, filtres Spring Security, RBAC par annotations, tests d'intégration Testcontainers sur les 3 rôles. | Suite de tests auth verte |
| J4 | Angular 18 : workspace, routing, guards, intercepteur JWT + refresh silencieux, layout (sidebar, topbar), pages login/register, gestion d'état (NgRx ou signals — décision documentée en ADR). | Login → dashboard vide protégé par rôle |
| J5 | FastAPI : squelette, `/health`, config Pydantic Settings, connexion PostgreSQL (asyncpg), premier conteneur ; CI GitHub Actions (lint + tests des 3 apps). Revue de semaine + démo 1. | Pipeline CI vert sur les 3 services |

### Semaine 2 — Ingestion des tickets (CSV, webhook) & pipeline asynchrone
| Jour | Travail | Livrable |
|---|---|---|
| J1 | Flyway V2 (imports, tickets), couche d'import **structuré** : CSV streaming (OpenCSV) + XLSX (Apache POI) + JSON/TXT tabulaire, détection MIME et d'encodage, validation ligne à ligne avec rapport d'erreurs. | CSV 10k lignes et XLSX importés sans OOM |
| J2 | Écran de mapping de colonnes (l'utilisateur associe ses colonnes CSV aux champs), prévisualisation 50 lignes, persistance du mapping réutilisable. | Import guidé de bout en bout |
| J3 | Chaîne asynchrone : publication `ticket.created` vers RabbitMQ (Spring AMQP), consumer FastAPI (aio-pika), acquittements, retries exponentiels, dead-letter queue, idempotence par `external_ref`. | Ticket traverse Spring → MQ → FastAPI |
| J4 | Webhook `POST /api/webhooks/tickets` (clé API + HMAC + rate limiting) ; liste des tickets Angular : table paginée serveur, tri, filtres de base. | Ingestion temps réel démontrable |
| J5 | Génération du dataset : script LLM de tickets synthétiques FR/EN (500–1000, catégories équilibrées, styles et longueurs variés), relecture manuelle d'un échantillon, gel du **test set** (300 tickets étiquetés à la main — jamais utilisé en entraînement). Démo 2. | Datasets `train.jsonl` / `test.jsonl` versionnés |

### Semaine 3 — Cœur IA : pipeline de triage hybride
| Jour | Travail | Livrable |
|---|---|---|
| J1 | Détection de langue FR/EN + **baselines de référence** (TF-IDF + LinearSVC, puis zero-shot LLM) évaluées sur le test set gelé ; analyse d'erreurs et augmentation ciblée du dataset (classes faibles). | Tableau baseline commité — le fine-tuning aura un point de comparaison honnête |
| J2 | Fine-tuning XLM-RoBERTa-base (Colab GPU) : multi-têtes priorité/catégorie/sentiment, suivi des runs, export ONNX pour inférence CPU rapide. | Modèle > baseline TF-IDF ; métriques loggées |
| J3 | Intégration LiteLLM (Groq → Gemini → OpenRouter → Ollama en failover), prompt de classification few-shot à sortie JSON, validation Pydantic + retry ; **routeur de confiance** (seuil 0.80, configurable). | Escalade LLM fonctionnelle et tracée |
| J4 | KeyBERT (mots-clés), embeddings multilingual-e5 → pgvector (HNSW), endpoint `/similar` ; règle de doublon (cosinus > 0.92 même catégorie) → suggestion de fusion. | "Tickets similaires" opérationnel |
| J5 | Harness d'évaluation v1 : F1 par classe sur le test set gelé, comparaison local seul / LLM seul / hybride ; intégration du harness en CI ; Langfuse branché. Démo 3 : import CSV → analyse complète visible. | Rapport d'éval chiffré commité |

### Semaine 4 — Dashboard, recherche, fiche ticket (livrable = cahier des charges couvert)
| Jour | Travail | Livrable |
|---|---|---|
| J1 | Vues SQL d'agrégation (KPIs, tendances) + endpoints dashboard ; cache Caffeine 60 s. | API dashboard < 100 ms |
| J2 | Dashboard Angular : cartes KPI (volume, % haute priorité, sentiment moyen, taux d'escalade LLM), graphiques Chart.js (évolution, répartition, heatmap horaire), filtres période. | Dashboard complet |
| J3 | Recherche full-text PostgreSQL (tsvector FR/EN + trigram fallback AR) combinée aux filtres structurés ; UI de recherche avec chips de filtres. | Recherche < 200 ms sur 50k tickets |
| J4 | Fiche ticket : analyse, badge de confiance, mots-clés, tickets similaires, **correction humaine** (dropdowns priorité/catégorie/sentiment → table annotations), fusion de doublons. | Boucle human-in-the-loop active |
| J5 | WebSocket STOMP : nouveaux tickets + alertes poussés en direct ; polish UI ; revue mi-parcours avec l'encadrant : **le cahier des charges initial est 100 % couvert à mi-stage**. Démo 4. | Jalon contractuel atteint |

### Semaine 5 — Agents LangGraph : Résolution (RAG) & base de connaissances
| Jour | Travail | Livrable |
|---|---|---|
| J1 | Base de connaissances : ingestion de documents (Markdown/PDF FAQ produits fictifs mais réalistes), chunking sémantique, embeddings → `kb_documents` ; écran admin KB (upload, liste, re-index). | KB interrogeable |
| J2 | Retrieval hybride : BM25 (rank_bm25) + vecteurs, fusion RRF, reranking cross-encoder ; éval recall@5 sur 40 paires question/chunk annotées. | Recall@5 mesuré et documenté |
| J3 | Agent Résolution en LangGraph : graphe retrieve → rerank → generate → self-check → retry ; état typé, checkpoints, citations obligatoires dans la sortie. | Brouillon cité généré sur ticket réel |
| J4 | UI brouillon : panneau dans la fiche ticket, citations cliquables (surlignage du chunk source), éditer/approuver/rejeter, statuts persistés, ton configurable (formel/empathique). | Workflow de validation complet |
| J5 | LLM-as-judge sur 50 brouillons (grille exactitude/complétude/ton), score stocké par brouillon, seuil "faible confiance" affiché ; ajout au harness CI. Démo 5. | Qualité RAG quantifiée |

### Semaine 6 — Agent Insight (Text-to-SQL) & Agent Digest
| Jour | Travail | Livrable |
|---|---|---|
| J1 | Vues read-only `v_*` + rôle PostgreSQL `insight_ro` ; agent Insight : schéma des vues dans le prompt, génération SQL, **validation AST** (sqlglot : SELECT only, vues whitelistées), timeout, limite de lignes. | SQL malveillant systématiquement bloqué (tests) |
| J2 | Boucle de réparation (erreur SQL → retry avec message), réponse en langage naturel + `chart_spec` JSON ; suite d'éval : 30 questions ↔ SQL de référence. | ≥ 80 % de réussite sur la suite |
| J3 | UI Chat Insight (rôle MANAGER) : conversation, rendu des graphiques depuis chart_spec, SQL affiché en mode transparent, questions suggérées. | Manager pose une question → graphique |
| J4 | Agent Digest : agrégats hebdo → synthèse LangGraph structurée → rendu Markdown + PDF (WeasyPrint) → envoi email (Spring Mail) ; scheduler Quartz lundi 8 h + bouton "générer maintenant". | Digest PDF envoyé automatiquement |
| J5 | Durcissement inter-agents : budgets de tokens par run, circuit breaker si quota LLM épuisé (dégradation vers Ollama local), journalisation `agent_runs` complète. Démo 6. | Résilience quota démontrée (coupure Groq simulée) |

### Semaine 7 — Intelligence proactive : sujets émergents, anomalies, risque SLA
| Jour | Travail | Livrable |
|---|---|---|
| J1 | Clustering des tickets récents (UMAP + HDBSCAN sur embeddings), étiquetage des clusters par LLM ("échec paiement mobile"), job périodique. | Sujets émergents listés avec taille/croissance |
| J2 | Détection d'anomalies de volume : décomposition STL par catégorie + z-score robuste ; création d'`alerts` + push WebSocket + panneau d'alertes UI avec acquittement. | Alerte déclenchée sur injection d'un pic simulé |
| J3 | Modèle de risque SLA : features (catégorie, priorité, sentiment, heure, backlog courant), gradient boosting (LightGBM), calibration ; score affiché + tri "at risk" dans la file. | AUC documentée ; colonne SLA risk dans la liste |
| J4 | Pipeline **unstructured-to-ticket** : extraction (PyMuPDF, python-docx, fallback OCR Tesseract sur PDF scannés) → structuration LLM (TicketBatch Pydantic, confiance par champ) → écran de validation avant insertion. Connecteur email IMAP branché sur ce même pipeline (nettoyage signatures/réponses citées). | Un PDF de 12 demandes glissé dans l'UI → 12 tickets validés puis analysés ; email → ticket automatique |
| J5 | Charge & robustesse : import 50k tickets, k6 sur les endpoints critiques (P95 < 300 ms), tuning index/requêtes N+1, test de résilience RabbitMQ (kill du worker en plein traitement). Démo 7. | Rapport de perf commité |

### Semaine 8 — Qualité finale, documentation, soutenance
| Jour | Travail | Livrable |
|---|---|---|
| J1 | Gel des fonctionnalités. Passe complète de bugs (board triage), tests E2E Cypress sur les 6 parcours critiques (login → import → analyse → correction → brouillon → insight). | Suite E2E verte |
| J2 | Sécurité : revue OWASP top 10, scan de dépendances, vérification prompt-injection (tickets piégés dans le RAG), audit des rôles. | Checklist sécurité signée |
| J3 | Documentation : README architecture + diagrammes (C4 niveau 2), 6–8 ADRs, guide d'installation, collection API, notice du harness d'évaluation, section "métriques IA" avec tous les chiffres. | Doc complète dans le repo |
| J4 | Rapport de stage (structure académique : contexte, état de l'art court, conception, réalisation, évaluation chiffrée, perspectives) + slides de soutenance (15 min) axées démo + chiffres. | Rapport v1 + deck |
| J5 | Répétition générale de la démo (scénario §14), vidéo de backup enregistrée, tag `v1.0`, rétrospective personnelle (ce que je referais différemment — excellent matériau d'entretien). | Soutenance prête |

---

## 10. Livrables finaux

1. Application Angular (dashboard, tickets, chat Insight, admin KB) — conteneurisée.
2. API Spring Boot (auth, ingestion, orchestration, WebSocket) — testée, documentée OpenAPI.
3. Service IA FastAPI (pipeline hybride + 4 agents LangGraph) — tracé Langfuse.
4. Base PostgreSQL + pgvector, migrations Flyway, vues analytiques.
5. Harness d'évaluation IA + rapport de métriques (F1 par classe, recall@k, score judge, suite Text-to-SQL).
6. Infrastructure : Docker Compose one-command, CI GitHub Actions.
7. Documentation technique (README, ADRs, C4) + rapport de stage + présentation finale + vidéo de démo.

---

## 11. Risques & plans B

| Risque | Probabilité | Mitigation / Plan B |
|---|---|---|
| Quotas LLM gratuits épuisés | Élevée | Failover LiteLLM multi-fournisseurs + Ollama local (qwen2.5:3b) ; le routeur de confiance minimise déjà les appels |
| Fine-tuning n'apportant pas de gain net vs baseline | Moyenne | Garder la meilleure option mesurée (baseline, fine-tuné ou hybride) ; documenter la comparaison — une décision chiffrée vaut mieux qu'un modèle imposé |
| Text-to-SQL peu fiable | Moyenne | Réduire aux 10 questions les plus utiles en mode "requêtes paramétrées + NL", garder le chat comme surcouche |
| Semaine 7 trop ambitieuse | Moyenne | F6/F7/F8 et la partie documentaire de F13 sont indépendants : en livrer 3 sur 4 sans casser le reste ; ordre de priorité F13-doc → F7 → F6 → F8 |
| Qualité OCR sur PDF scannés | Moyenne | OCR en fallback uniquement (extraction native d'abord), confiance par champ affichée, validation humaine obligatoire — les formats structurés restent le chemin principal |
| Retard général | — | Le cahier des charges est couvert dès la fin S4 : tout retard ampute les extensions, jamais le contractuel |

---

## 12. Ce que ce projet démontre à un recruteur

- **Architecture distribuée réelle** : messaging asynchrone, contrats d'API, résilience — pas un CRUD.
- **IA de production, pas de notebook** : routage coût/latence, validation de schémas, observabilité, évaluation quantitative, human-in-the-loop, guardrails.
- **LangGraph multi-agents avec cas d'usage métier concrets** (triage, résolution, insight, digest) — au-delà du chatbot générique.
- **Rigueur expérimentale** : baselines mesurées avant fine-tuning, test set gelé, décisions de modèle justifiées par les chiffres.
- **Discipline d'ingénieur** : tests, CI, migrations, ADRs, gestion de périmètre avec plans B.

Formulations prêtes pour le CV (à chiffrer avec les vraies métriques en fin de stage) :
- "Conçu une plateforme multi-agents (LangGraph) d'analyse et de résolution de tickets support, réduisant le coût d'inférence de X % via un routage hybride classifieur fine-tuné / LLM."
- "Implémenté un pipeline RAG cité avec validation humaine et évaluation LLM-as-judge (score moyen X/5 sur N brouillons)."
- "Développé un agent Text-to-SQL sécurisé (validation AST, vues read-only) atteignant X % de réussite sur une suite de 30 questions."
- "Conçu une couche d'ingestion universelle (CSV, XLSX, JSON, PDF, DOCX, email, OCR) avec structuration LLM validée par schéma et revue humaine avant insertion."

---

## 13. Scénario de démonstration (12 minutes)

1. `docker compose up` — tout démarre (30 s, en accéléré).
2. Login MANAGER → dashboard vide → import CSV 5 000 tickets → la file RabbitMQ se vide en direct, les KPIs se remplissent via WebSocket.
3. Ouverture d'un ticket ambigu → escalade LLM visible (badge "analysé par LLM", justification), tickets similaires, doublon suggéré.
4. Glisser-déposer d'un **PDF** contenant une dizaine de demandes clients → extraction + structuration LLM → écran de validation (champs à faible confiance surlignés) → confirmation → tickets analysés.
5. Génération du brouillon de réponse → citations cliquables → édition → approbation.
6. Chat Insight : "Quelle catégorie a le plus augmenté cette semaine et pourquoi ?" → SQL affiché + graphique + synthèse.
7. Injection d'un pic simulé de tickets "paiement" via le webhook → alerte d'anomalie en temps réel + sujet émergent détecté.
8. Écran "Qualité IA" : F1 par classe, taux d'escalade, latences — l'IA qui prouve sa valeur.
9. Coupure simulée de Groq → failover Ollama transparent.

---

## 14. Questions à valider avec l'encadrant (semaine 1, jour 1)

1. Y a-t-il des **données réelles** de tickets (même anonymisées) ou tout sera synthétique ?
2. Le connecteur **email IMAP** est-il pertinent pour l'entreprise, ou le webhook suffit-il ?
3. Contrainte de **déploiement** : démo locale Docker Compose suffisante, ou un déploiement (VPS/cloud) est-il attendu ?
4. Volumétrie cible réaliste (10k ? 100k tickets ?) pour calibrer les tests de charge.
5. La base de connaissances (FAQ/docs) existe-t-elle chez le client, ou faut-il en construire une fictive ?
6. Répartition FR/EN attendue dans les tickets réels (pour équilibrer le dataset d'entraînement).

---

## 15. Squelette du repo — fichiers complets

> **Instruction pour Claude Code / Claude Desktop :** recréer exactement cette arborescence et ces fichiers dans un dossier `supportiq/`, puis exécuter `git init`, `cp .env.example .env` et `docker compose up -d postgres rabbitmq ai-service` pour vérifier le health check (`curl http://localhost:8001/health`). Créer aussi les fichiers vides : `ai-service/app/__init__.py`, `ai-service/app/api/__init__.py`, `ai-service/app/core/__init__.py`, `ai-service/app/pipeline/__init__.py`, `eval/datasets/.gitkeep`.

### Arborescence

```
supportiq/
├── README.md
├── .gitignore
├── .env.example
├── docker-compose.yml
├── .github/workflows/ci.yml
├── infra/postgres/init.sql
├── ai-service/
│   ├── Dockerfile
│   ├── requirements.txt · requirements-dev.txt
│   ├── app/
│   │   ├── __init__.py · main.py · config.py · schemas.py
│   │   ├── api/routes.py
│   │   ├── core/llm.py
│   │   ├── pipeline/triage.py
│   │   └── agents/__init__.py
│   └── tests/test_health.py
├── backend/README.md        (généré par Spring Initializr au J2)
├── frontend/README.md       (généré par Angular CLI au J4)
├── eval/README.md · eval/datasets/.gitkeep
└── docs/adr/0001-architecture-generale.md
```

### `README.md`

````markdown
# SupportIQ — Plateforme Agentique d'Analyse & de Résolution des Tickets de Support

Monorepo du projet de stage. Voir `docs/` pour l'architecture et les ADRs.

## Structure
```
frontend/     Angular 18 SPA (dashboard, tickets, chat Insight)
backend/      Spring Boot 3 — API REST, auth JWT, ingestion, orchestration
ai-service/   FastAPI — pipeline NLP hybride + agents LangGraph
infra/        Scripts d'infrastructure (init PostgreSQL, etc.)
eval/         Datasets gelés + harness d'évaluation IA
docs/         Architecture, ADRs, guides
```

## Démarrage rapide (infra + service IA)
```bash
cp .env.example .env        # renseigner les clés API (Groq, Gemini...)
docker compose up -d postgres rabbitmq
docker compose up ai-service
# health check :
curl http://localhost:8001/health
```

## Générer le backend (jour 2)
Voir `backend/README.md` — Spring Initializr avec la liste exacte des dépendances.

## Générer le frontend (jour 4)
Voir `frontend/README.md` — Angular CLI + dépendances.
````

### `.gitignore`

````text
# Node / Angular
node_modules/
dist/
.angular/

# Java / Spring
target/
build/
*.class
.gradle/

# Python
__pycache__/
*.pyc
.venv/
.pytest_cache/
.ruff_cache/

# Env & IDE
.env
.idea/
.vscode/
*.iml

# Data & modèles (versionnés ailleurs / trop lourds)
*.onnx
*.bin
eval/datasets/*.jsonl
!eval/datasets/.gitkeep
````

### `.env.example`

````text
# PostgreSQL
POSTGRES_USER=supportiq
POSTGRES_PASSWORD=firas
POSTGRES_DB=supportiq

# RabbitMQ
RABBITMQ_DEFAULT_USER=supportiq
RABBITMQ_DEFAULT_PASS=firas

# JWT (backend)
JWT_SECRET=generate-a-long-random-secret

# LLM providers (au moins un requis ; failover dans cet ordre)
GROQ_API_KEY=
GEMINI_API_KEY=
OPENROUTER_API_KEY=
OLLAMA_BASE_URL=http://ollama:11434

# Seuil d'escalade LLM du routeur de confiance
CONFIDENCE_THRESHOLD=0.80
````

### `docker-compose.yml`

````yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_DB: ${POSTGRES_DB}
    ports: ["5432:5432"]
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./infra/postgres/init.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER}"]
      interval: 5s
      retries: 10

  rabbitmq:
    image: rabbitmq:3.13-management
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_DEFAULT_USER}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_DEFAULT_PASS}
    ports: ["5672:5672", "15672:15672"]  # 15672 = UI de management
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 5s
      retries: 10

  ai-service:
    build: ./ai-service
    env_file: .env
    environment:
      DATABASE_URL: postgresql+asyncpg://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${POSTGRES_DB}
      RABBITMQ_URL: amqp://${RABBITMQ_DEFAULT_USER}:${RABBITMQ_DEFAULT_PASS}@rabbitmq:5672/
    ports: ["8001:8001"]
    depends_on:
      postgres: { condition: service_healthy }
      rabbitmq: { condition: service_healthy }
    volumes:
      - ./ai-service/app:/srv/app   # hot-reload en dev

  # --- À activer au fil du projet -------------------------------------
  # backend:        # Semaine 1, J2 — après génération Spring Initializr
  #   build: ./backend
  #   env_file: .env
  #   ports: ["8080:8080"]
  #   depends_on:
  #     postgres: { condition: service_healthy }
  #     rabbitmq: { condition: service_healthy }

  # frontend:       # Semaine 1, J4 — après génération Angular CLI
  #   build: ./frontend
  #   ports: ["4200:80"]

  # ollama:         # Semaine 6 — fallback LLM local
  #   image: ollama/ollama
  #   ports: ["11434:11434"]
  #   volumes: [ollama:/root/.ollama]

  # langfuse:       # Semaine 3, J5 — observabilité LLM (voir docs Langfuse self-host)

volumes:
  pgdata:
  # ollama:
````

### `.github/workflows/ci.yml`

````yaml
name: CI

on:
  push: { branches: [main] }
  pull_request:

jobs:
  ai-service:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: ai-service } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: "3.12", cache: pip }
      - run: pip install -r requirements-dev.txt
      - run: ruff check app tests
      - run: pytest -q

  # Activé au J2 (semaine 1) après génération du backend
  # backend:
  #   runs-on: ubuntu-latest
  #   defaults: { run: { working-directory: backend } }
  #   steps:
  #     - uses: actions/checkout@v4
  #     - uses: actions/setup-java@v4
  #       with: { distribution: temurin, java-version: "21", cache: maven }
  #     - run: ./mvnw -B verify

  # Activé au J4 (semaine 1) après génération du frontend
  # frontend:
  #   runs-on: ubuntu-latest
  #   defaults: { run: { working-directory: frontend } }
  #   steps:
  #     - uses: actions/checkout@v4
  #     - uses: actions/setup-node@v4
  #       with: { node-version: "20", cache: npm, cache-dependency-path: frontend/package-lock.json }
  #     - run: npm ci
  #     - run: npm run lint && npm test -- --watch=false --browsers=ChromeHeadless

  # Activé au J5 (semaine 3) : harness d'évaluation IA
  # eval:
  #   runs-on: ubuntu-latest
  #   steps: [...]  # F1 par classe sur eval/datasets/test.jsonl — échec si régression > 2 pts
````

### `infra/postgres/init.sql`

````sql
-- Exécuté au premier démarrage du conteneur PostgreSQL
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- recherche trigram (fallback)
CREATE EXTENSION IF NOT EXISTS unaccent;  -- normalisation accents FR

-- Rôle read-only dédié à l'agent Insight (Text-to-SQL) — durci en semaine 6
-- CREATE ROLE insight_ro LOGIN PASSWORD '...';
-- GRANT SELECT ON v_ticket_stats, v_category_trends TO insight_ro;
````

### `ai-service/Dockerfile`

````dockerfile
FROM python:3.12-slim
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
EXPOSE 8001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001", "--reload"]
````

### `ai-service/requirements.txt`

````text
fastapi>=0.115
uvicorn[standard]>=0.30
pydantic>=2.8
pydantic-settings>=2.4
litellm>=1.44
langgraph>=0.2
langchain-core>=0.3
asyncpg>=0.29
aio-pika>=9.4
sentence-transformers>=3.0
httpx>=0.27
# Ajoutés au fil des semaines : keybert, rank-bm25, sqlglot, lightgbm, bertopic, langfuse,
# pymupdf, python-docx, pytesseract (ingestion documentaire S7)
````

### `ai-service/requirements-dev.txt`

````text
-r requirements.txt
pytest>=8.2
pytest-asyncio>=0.23
ruff>=0.5
````

### `ai-service/app/main.py`

````python
from fastapi import FastAPI

from app.api.routes import router

app = FastAPI(title="SupportIQ — AI Service", version="0.1.0")
app.include_router(router)
````

### `ai-service/app/config.py`

````python
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str = "postgresql+asyncpg://supportiq:firas@localhost:5432/supportiq"
    rabbitmq_url: str = "amqp://supportiq:firas@localhost:5672/"
    confidence_threshold: float = 0.80
    groq_api_key: str = ""
    gemini_api_key: str = ""
    openrouter_api_key: str = ""
    ollama_base_url: str = "http://localhost:11434"

    class Config:
        env_file = ".env"
        extra = "ignore"


settings = Settings()
````

### `ai-service/app/schemas.py`

````python
from enum import Enum
from pydantic import BaseModel, Field


class Priority(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class Category(str, Enum):
    TECHNIQUE = "TECHNIQUE"
    FACTURATION = "FACTURATION"
    COMPTE = "COMPTE"
    RECLAMATION = "RECLAMATION"
    DEMANDE = "DEMANDE"


class Sentiment(str, Enum):
    NEG = "NEG"
    NEU = "NEU"
    POS = "POS"


class AnalyzeRequest(BaseModel):
    ticket_id: int | None = None
    text: str = Field(min_length=1)
    language: str | None = None  # "fr" | "en" — détectée si absente


class AnalysisResult(BaseModel):
    """Contrat de sortie strict — toute réponse LLM est validée contre ce schéma."""
    priority: Priority
    category: Category
    sentiment: Sentiment
    keywords: list[str] = []
    confidence: float = Field(ge=0, le=1)
    language: str
    model_used: str
    escalated_to_llm: bool = False
````

### `ai-service/app/api/routes.py`

````python
from fastapi import APIRouter

from app.schemas import AnalyzeRequest, AnalysisResult

router = APIRouter()


@router.get("/health")
async def health() -> dict:
    return {"status": "ok", "service": "supportiq-ai"}


@router.post("/analyze", response_model=AnalysisResult)
async def analyze(req: AnalyzeRequest) -> AnalysisResult:
    from app.pipeline.triage import analyze as run

    return await run(req)
````

### `ai-service/app/core/llm.py`

````python
"""Passerelle LLM unique via LiteLLM — failover Groq -> Gemini -> OpenRouter -> Ollama.

Toute la logique d'appel LLM du projet passe par ici : un seul point
d'instrumentation (Langfuse), de retry et de budget de tokens.
"""
import litellm

from app.config import settings

FALLBACK_CHAIN = [
    "groq/llama-3.3-70b-versatile",
    "gemini/gemini-2.0-flash",
    "openrouter/meta-llama/llama-3.3-70b-instruct:free",
    f"ollama/qwen2.5:3b",
]


async def complete(messages: list[dict], response_format: dict | None = None) -> str:
    last_error: Exception | None = None
    for model in FALLBACK_CHAIN:
        try:
            resp = await litellm.acompletion(
                model=model,
                messages=messages,
                response_format=response_format,
                max_tokens=1024,
                timeout=30,
            )
            return resp.choices[0].message.content
        except Exception as exc:  # quota épuisé, timeout, provider down
            last_error = exc
            continue
    raise RuntimeError(f"Tous les fournisseurs LLM ont échoué: {last_error}")
````

### `ai-service/app/pipeline/triage.py`

````python
"""Pipeline de triage hybride (F1).

Semaine 3 :
  J1  baselines (TF-IDF + LinearSVC, zero-shot LLM) sur le test set gelé
  J2  fine-tuning XLM-RoBERTa multi-têtes, export ONNX
  J3  routeur de confiance : local si conf >= seuil, sinon escalade LLM
"""
from app.config import settings
from app.schemas import AnalyzeRequest, AnalysisResult


async def analyze(req: AnalyzeRequest) -> AnalysisResult:
    raise NotImplementedError("Implémenté en semaine 3 — voir planning J1-J3")
````

### `ai-service/app/agents/__init__.py`

````python
"""Agents LangGraph — un module par agent, chacun un graphe explicite.

    triage.py      (S3)  orchestration du pipeline + doublons
    resolution.py  (S5)  RAG cité : retrieve -> rerank -> generate -> self-check
    insight.py     (S6)  Text-to-SQL sécurisé (validation AST, vues read-only)
    digest.py      (S6)  rapport hebdomadaire structuré
"""
````

### `ai-service/tests/test_health.py`

````python
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health():
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"
````

### `backend/README.md`

````markdown
# Backend — Spring Boot 3 (généré au J2, semaine 1)

## Génération (Spring Initializr)
```bash
curl https://start.spring.io/starter.zip \
  -d type=maven-project -d language=java -d javaVersion=21 \
  -d bootVersion=3.4.1 \
  -d groupId=com.supportiq -d artifactId=backend -d name=supportiq-backend \
  -d dependencies=web,security,data-jpa,postgresql,flyway,validation,amqp,websocket,actuator,lombok \
  -o backend.zip && unzip backend.zip -d .
```

## Dépendances à ajouter manuellement au pom.xml
- `io.jsonwebtoken:jjwt-api / jjwt-impl / jjwt-jackson` (0.12.x) — JWT
- `com.opencsv:opencsv` — parsing CSV en streaming
- `com.bucket4j:bucket4j-core` — rate limiting du webhook
- `org.testcontainers:postgresql` + `junit-jupiter` (test) — tests d'intégration
- `com.github.ben-manes.caffeine:caffeine` — cache dashboard

## Conventions
- Migrations : `src/main/resources/db/migration/V1__users_auth.sql`, `V2__tickets_imports.sql`...
- Erreurs : ProblemDetail (RFC 7807) via un @RestControllerAdvice global
- Un package par module : auth / imports / tickets / dashboard / messaging / webhook
````

### `frontend/README.md`

````markdown
# Frontend — Angular 18 (généré au J4, semaine 1)

## Génération
```bash
npm i -g @angular/cli@18
ng new supportiq-frontend --directory . --routing --style scss --ssr false
npm i chart.js ng2-charts @stomp/stompjs
```

## Structure cible
```
src/app/
  core/        intercepteur JWT + refresh silencieux, guards, services API
  features/
    auth/      login, register
    dashboard/ cartes KPI, graphiques Chart.js, alertes temps réel
    tickets/   liste paginée serveur, filtres, fiche ticket, annotations
    insight/   chat manager (Text-to-SQL), rendu chart_spec
    admin/     utilisateurs, base de connaissances
  shared/      composants UI, pipes, directives
```

## Décision à documenter (ADR)
NgRx vs Angular signals pour l'état global — trancher au J4 et écrire `docs/adr/000X-etat-frontend.md`.
````

### `eval/README.md`

````markdown
# Harness d'évaluation IA

- `datasets/train.jsonl` — dataset d'entraînement (synthétique + public), versionné hors Git (trop lourd)
- `datasets/test.jsonl` — **test set gelé** : ~300 tickets étiquetés à la main en S2-J5.
  Règle absolue : jamais utilisé en entraînement, jamais modifié après gel.

## Suites (ajoutées au fil des semaines)
1. S3-J5  Classification : precision / recall / F1 par classe — local vs LLM vs hybride
2. S5-J2  Retrieval : recall@5 sur 40 paires question/chunk annotées
3. S5-J5  Brouillons RAG : LLM-as-judge (exactitude, complétude, ton) sur 50 brouillons
4. S6-J2  Text-to-SQL : 30 questions avec SQL de référence, comparaison des résultats
````

### `docs/adr/0001-architecture-generale.md`

````markdown
# ADR-0001 — Architecture générale : plan de contrôle / plan de calcul

Date : S1-J1 · Statut : accepté

## Contexte
Stack imposée : Angular, Spring Boot, PostgreSQL, Python/FastAPI. Besoin d'analyse
IA asynchrone sur des volumes d'import importants (10k+ tickets par CSV).

## Décision
- Spring Boot = plan de contrôle : sécurité, transactions, règles métier, orchestration.
- FastAPI = plan de calcul : NLP, agents LangGraph, embeddings.
- Communication asynchrone via RabbitMQ (ticket.created / ticket.analyzed),
  appels synchrones REST réservés aux besoins interactifs (brouillon, insight).
- pgvector dans PostgreSQL plutôt qu'un vector store séparé (ACID, jointures, zéro infra en plus).

## Conséquences
+ Découplage, résilience (retry/DLQ), scalabilité du worker IA
+ Un seul système de stockage à opérer
- Complexité MQ à maîtriser (idempotence, acquittements) — couverte en S2-J3

## ADRs suivants à écrire
0002 état frontend (NgRx vs signals) · 0003 stratégie de fine-tuning vs baseline ·
0004 seuil du routeur de confiance · 0005 retrieval hybride RRF · 0006 guardrails Text-to-SQL
````

