# Revue mi-parcours — couverture du cahier des charges (fin Semaine 4)

Jalon contractuel du rapport §9 (S4-J5) : **le cahier des charges initial est couvert à mi-stage.**
Ce document sert de support à la revue avec l'encadrant. Chaque ligne est vérifiable en démo.

## Fonctionnalités du cœur (rapport §2)

| Réf. | Fonctionnalité | État | Où le voir / preuve |
|---|---|---|---|
| **F1** | Triage automatique (priorité, catégorie, sentiment, langue) | ✅ | Fiche ticket ; pipeline hybride local+LLM ; détection FR/EN à **98 %** |
| **F2** | Ingestion multi-format (CSV, XLSX, JSON, TXT) | ✅ | Écran Imports : CSV **10 000 lignes sans OOM**, mapping de colonnes guidé |
| **F3** | Recherche & filtres | ✅ | Écran Tickets : full-text FR/EN (tsvector + GIN), **0,217 ms** mesuré, chips de filtres |
| **F4** | Dashboard & KPIs | ✅ | 5 cartes KPI + 5 graphiques Chart.js, filtre période ; API **~5 ms** (cache 60 s) |
| **F5** | Détection de doublons | ✅ | Similarité pgvector (HNSW) ; doublon détecté à **0,9806** + fusion |
| **F9** | Ingestion temps réel (webhook) | ✅ | `POST /api/webhooks/tickets` : clé API + **HMAC-SHA256** + rate limiting (429) |
| **F10** | Human-in-the-loop & active learning | ✅ | Correction en 1 clic → table `annotations` (`predicted` **et** `corrected` conservés) |
| **F11** | Observabilité & évaluation | ✅ | Harness d'éval sur test set gelé, ADR-0003/0004 chiffrés, Langfuse branché (optionnel) |
| **F12** | Temps réel UI (WebSocket) | ✅ | Badge « live », bandeau « N nouveaux tickets », boucle `ticket.created`/`ticket.analyzed` |

## Ce qui reste au programme (Semaines 5-8, stretch assumé)

| Réf. | Fonctionnalité | Semaine |
|---|---|---|
| F6 | Brouillons de réponse RAG + base de connaissances | S5 |
| — | Agent Insight (Text-to-SQL sécurisé) | S6 |
| F7 | Détection d'anomalies de volume | S7 |
| F8 | Prédiction de risque SLA | S7 |
| F13 | Documentation finale, déploiement, soutenance | S8 |

## Chiffres à citer en revue

- **Ingestion** : 10 000 tickets importés en streaming, sans dépassement mémoire.
- **Classification (test set gelé de 300 tickets, jamais vu à l'entraînement)** :
  catégorie **macro-F1 0,95**, sentiment **0,60**. Priorité **non apprenable du texte**
  (0,33-0,40 quel que soit le modèle) → **dérivée par règles**, décision documentée (ADR-0003).
- **Coût/qualité** : seuil d'escalade LLM calibré à **0,50** — monter le seuil double le nombre
  d'appels LLM pour +0,03 de F1 et dégrade la catégorie (ADR-0004).
- **Performance** : dashboard **~5 ms**, recherche **0,217 ms** (index GIN confirmé par `EXPLAIN ANALYZE`).
- **Qualité** : 4 jobs CI verts (lint déterministe, tests d'intégration Testcontainers sur PostgreSQL réel,
  garde-fou d'intégrité du test set gelé).

## Architecture, en une phrase

Angular 18 (SPA) → Spring Boot 3 (**plan de contrôle** : sécurité, transactions, orchestration) →
PostgreSQL 16 + pgvector, avec RabbitMQ pour découpler l'analyse et FastAPI comme **plan de calcul**
(NLP, embeddings, LLM). Le tout démarre avec un seul `docker compose up`.

## Points d'honnêteté à assumer devant l'encadrant

- Le dataset est **synthétique** (généré par LLM avec filtre d'accord) : la mécanique et les métriques
  sont valides, mais les chiffres seraient à re-mesurer sur des tickets réels.
- La **priorité** n'est pas prédite par un modèle : c'est un choix argumenté, pas un manque.
- L'UI est fonctionnelle mais volontairement sobre (Angular Material) ; le temps a été investi dans
  l'architecture et l'évaluation.
