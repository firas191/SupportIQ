# Backend — Spring Boot 3 (Semaine 1, Jour 2)

> Projet **rédigé à la main** (le proxy du bac à sable bloquant `start.spring.io`). La commande
> Initializr ci-dessous reste la référence de scaffolding : le `pom.xml` livré correspond aux
> mêmes dépendances de base. La couche `security` est volontairement **différée au J3** (voir plus bas).

## Référence de génération (Spring Initializr)
```bash
curl https://start.spring.io/starter.zip \
  -d type=maven-project -d language=java -d javaVersion=21 \
  -d bootVersion=3.4.1 \
  -d groupId=com.supportiq -d artifactId=backend -d name=supportiq-backend \
  -d dependencies=web,security,data-jpa,postgresql,flyway,validation,amqp,websocket,actuator,lombok \
  -o backend.zip && unzip backend.zip -d .
```

## Contenu livré au J2
```
backend/
├── pom.xml                        Boot 3.4.1, Java 21
└── src/main/
    ├── java/com/supportiq/backend/
    │   ├── SupportiqBackendApplication.java
    │   ├── auth/                  User, RefreshToken, Role, *Repository
    │   └── common/error/          GlobalExceptionHandler (ProblemDetail), ResourceNotFoundException
    └── resources/
        ├── application.yml        commun (datasource, ddl-auto=validate, flyway, actuator)
        ├── application-dev.yml    profil dev (show-sql, logs verbeux)
        ├── application-prod.yml   profil prod (secrets via env uniquement)
        └── db/migration/V1__users_auth.sql
```

Dépendances actives au J2 : `web`, `validation`, `data-jpa`, `flyway-core` +
`flyway-database-postgresql` (requis par Boot 3.x pour PostgreSQL), `actuator`, `postgresql`
(runtime), `lombok`, `spring-boot-starter-test`.

## Dépendances à ajouter au fil du planning
- `spring-boot-starter-security` + `io.jsonwebtoken:jjwt-api / jjwt-impl / jjwt-jackson` (0.12.x) — **J3**
- `spring-boot-starter-amqp` — Semaine 2 (chaîne asynchrone RabbitMQ)
- `spring-boot-starter-websocket` — Semaine 4 (STOMP)
- `com.opencsv:opencsv` — Semaine 2 (import CSV streaming)
- `com.bucket4j:bucket4j-core` — Semaine 2 (rate limiting webhook)
- `org.testcontainers:postgresql` + `junit-jupiter` (test) — **J3** (tests d'intégration)
- `com.github.ben-manes.caffeine:caffeine` — Semaine 4 (cache dashboard)

## Build & exécution locale (Java 21 requis)
```bash
# (optionnel) générer le wrapper Maven pour disposer de ./mvnw :
mvn -N wrapper:wrapper

# lancer Postgres (depuis la racine du monorepo) :
docker compose up -d postgres

# démarrer l'API (profil dev par défaut) :
mvn spring-boot:run          # ou ./mvnw spring-boot:run

# vérifier :
curl http://localhost:8080/actuator/health     # attendu : {"status":"UP"}
```
Au démarrage, Flyway applique `V1` puis Hibernate **valide** le schéma (`ddl-auto=validate`) —
il ne génère jamais de DDL, Flyway est l'unique propriétaire du schéma.

## Conventions
- Migrations : `src/main/resources/db/migration/V1__users_auth.sql`, `V2__tickets_imports.sql`...
  Jamais éditer une migration déjà appliquée.
- Erreurs : `ProblemDetail` (RFC 7807) via le `@RestControllerAdvice` global — pas de stacktrace exposée.
- Un package par module : `auth / imports / tickets / dashboard / messaging / webhook` (+ `common` transverse).
