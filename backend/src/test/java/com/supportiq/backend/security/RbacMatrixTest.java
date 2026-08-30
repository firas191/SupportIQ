package com.supportiq.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Audit des roles, sous forme executable (S8-J2).
 *
 * <h2>Pourquoi un test et pas un tableau dans un document</h2>
 *
 * <p>Le livrable demande par le rapport §9 est une « checklist securite signee ». Une checklist ou
 * l'on coche « /api/dashboard : MANAGER » est vraie le jour ou on l'ecrit, et personne ne saura le
 * jour ou elle cesse de l'etre. Ici, un controleur ajoute sans son {@code @PreAuthorize} fait
 * echouer la CI, et un endpoint retire aussi.
 *
 * <h2>Ce que ce test verifie, et rien d'autre</h2>
 *
 * <p>Uniquement l'<b>autorisation</b> : pour chaque route et chaque role, la reponse est-elle 401,
 * 403, ou autre chose ? Le « autre chose » n'est pas inspecte — un 404, un 400 ou un 409 valent
 * acceptation, puisque la requete a franchi le controle d'acces. Melanger autorisation et
 * comportement metier rendrait la matrice illisible et fragile.
 *
 * <h2>L'autorisation vient de deux endroits, et c'est la le risque</h2>
 *
 * <ul>
 *   <li>{@code SecurityConfig} : quatre groupes en {@code permitAll} puis
 *       {@code anyRequest().authenticated()} — qui decide « faut-il un jeton ? » ;</li>
 *   <li>{@code @PreAuthorize} sur les controleurs — qui decide « quel role ? ».</li>
 * </ul>
 *
 * <p>Un endpoint sans annotation est donc ouvert a <b>tout utilisateur authentifie</b>, quel que
 * soit son role. C'est voulu pour {@code /api/tickets} (§7 : AGENT+), et ce serait une faille pour
 * n'importe quel ecran d'administration. Rien dans le code ne distingue les deux cas : seule cette
 * matrice le dit, ligne par ligne.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RbacMatrixTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.security.jwt.secret",
                () -> "test-secret-supportiq-0123456789-abcdefghijklmnop");
        registry.add("app.bootstrap.admin.email", () -> "admin@supportiq.local");
        registry.add("app.bootstrap.admin.password", () -> "admin1234");
        registry.add("spring.rabbitmq.port", () -> "1");
        // Les ordonnanceurs n'ont rien a faire dans un test d'autorisation, et leurs appels sortants
        // ralentiraient chaque classe pour rien.
        registry.add("app.analysis-recovery.enabled", () -> "false");
        registry.add("app.alerts.auto-detect", () -> "false");
        registry.add("app.ai-service.base-url", () -> "http://localhost:1");
    }

    /** Niveau d'acces attendu. L'ordre suit la hierarchie ADMIN &gt; MANAGER &gt; AGENT. */
    private enum Access {
        /** Aucun jeton requis. */
        PUBLIC,
        /**
         * Ouvert au niveau de Spring Security, mais <b>authentifie par le service lui-meme</b>.
         *
         * <p>Le seul cas est le webhook : cle API + signature HMAC verifiees dans le controleur
         * (S2-J4). Un systeme externe ne fait pas le flux de connexion, donc le laisser passer la
         * chaine de filtres est deliberé — et il refuse ensuite tout le monde, y compris un ADMIN
         * muni d'un jeton valide, tant que la signature manque.
         *
         * <p>Distinguer ce cas de {@code PUBLIC} n'est pas une subtilite : les confondre ferait
         * passer pour normal un 401 qui, sur une route vraiment publique, serait une regression.
         * Sa protection reelle est verifiee par {@code WebhookIntegrationTest}, pas ici.
         */
        PUBLIC_APP_AUTH,
        /** Jeton requis, n'importe quel role. */
        AUTHENTICATED,
        AGENT,
        MANAGER,
        ADMIN
    }

    /**
     * Corps de requete valides, pour les routes ou la validation precede l'autorisation.
     *
     * <p><b>Le piege que ceci corrige.</b> Ma premiere version envoyait {@code {}} partout. Or Spring
     * MVC resout et <b>valide</b> les arguments avant d'invoquer la methode, donc avant
     * l'intercepteur {@code @PreAuthorize} : un AGENT postant un corps vide sur
     * {@code /api/auth/register} recevait <b>400</b>, pas 403. Le test passait pour un audit alors
     * qu'il ne franchissait jamais le controle d'acces.
     *
     * <p>Consequence a noter au passage, cote produit : sur ces routes, un utilisateur authentifie
     * mais non autorise apprend qu'un endpoint existe et ce qu'il attend, avant d'apprendre qu'il
     * n'y a pas droit. Divulgation mineure — l'action n'est jamais executee — mais reelle, et elle
     * n'aurait pas ete visible sans cette matrice.
     */
    private static final Map<String, String> BODIES = Map.of(
            "POST /api/auth/register",
            "{\"email\":\"rbac-probe@supportiq.local\",\"password\":\"secret1234\","
                    + "\"fullName\":\"Sonde RBAC\",\"role\":\"AGENT\"}",
            "POST /api/insight/questions", "{\"question\":\"Combien de tickets ?\"}",
            "POST /api/imports/1/confirm", "{\"mapping\":{\"subject\":\"sujet\"}}",
            "POST /api/tickets/1/annotations", "{\"field\":\"category\",\"value\":\"FACTURATION\"}",
            "POST /api/tickets/1/merge", "{\"targetId\":2}",
            "POST /api/kb/search", "{\"query\":\"remboursement\"}");

    /**
     * Routes attendant un <b>multipart</b>. Un corps JSON y provoque un 415 avant toute
     * verification de role — meme mecanisme que ci-dessus, autre symptome.
     */
    private static final Set<String> MULTIPART = Set.of(
            "POST /api/imports", "POST /api/kb/documents", "POST /api/intake/documents");

    /**
     * <b>La matrice.</b> Chaque route de l'API y figure, avec le niveau minimal attendu.
     *
     * <p>Les valeurs viennent du rapport §7, pas du code : c'est une <b>specification</b> qu'on
     * confronte a l'implementation. La recopier depuis les annotations en ferait une tautologie —
     * elle passerait quoi qu'on ecrive, y compris une regression.
     *
     * <p>Les identifiants de chemin sont concretises par {@code 1} : la ressource n'existe pas, la
     * reponse sera un 404 ou un 409, et c'est sans importance — seul compte le fait d'avoir franchi
     * le controle d'acces.
     */
    private static final Map<String, Access> MATRIX = new LinkedHashMap<>() {{
        // --- Authentification -------------------------------------------------
        put("POST /api/auth/login", Access.PUBLIC);
        put("POST /api/auth/refresh", Access.PUBLIC);
        put("POST /api/auth/logout", Access.PUBLIC);
        put("GET /api/auth/me", Access.AUTHENTICATED);
        // Pas d'inscription libre : creer un compte est une action d'administration.
        put("POST /api/auth/register", Access.ADMIN);

        // --- Webhook : hors JWT, authentifie par cle API + HMAC dans le service.
        // C'est la seule route ouverte qui ecrit en base — sa protection reelle est verifiee par
        // WebhookIntegrationTest, pas ici.
        put("POST /api/webhooks/tickets", Access.PUBLIC_APP_AUTH);

        // --- Tickets : tout agent travaille dessus, c'est l'ecran principal ----
        put("GET /api/tickets", Access.AUTHENTICATED);
        put("GET /api/tickets/1", Access.AUTHENTICATED);
        put("POST /api/tickets/1/annotations", Access.AUTHENTICATED);
        put("POST /api/tickets/1/merge", Access.AUTHENTICATED);

        // --- Brouillons : la boucle humaine est ouverte a tout agent ----------
        put("POST /api/tickets/1/draft", Access.AGENT);
        put("GET /api/tickets/1/draft", Access.AGENT);
        put("PATCH /api/drafts/1", Access.AGENT);
        put("POST /api/drafts/1/send", Access.AGENT);

        // --- Depot documentaire : traitement, pas administration --------------
        put("POST /api/intake/documents", Access.AGENT);
        put("POST /api/intake/confirm", Access.AGENT);

        // --- Vues d'ensemble : agregent l'activite de toute l'equipe ----------
        put("GET /api/dashboard/kpis", Access.MANAGER);
        put("GET /api/dashboard/trends", Access.MANAGER);
        put("POST /api/insight/questions", Access.MANAGER);
        put("GET /api/topics", Access.MANAGER);
        put("POST /api/topics/detect", Access.MANAGER);
        put("GET /api/alerts", Access.MANAGER);
        put("GET /api/alerts/count", Access.MANAGER);
        put("POST /api/alerts/1/ack", Access.MANAGER);
        put("POST /api/alerts/detect", Access.MANAGER);
        put("GET /api/digests", Access.MANAGER);
        put("GET /api/digests/status", Access.MANAGER);
        put("GET /api/digests/1/pdf", Access.MANAGER);
        put("POST /api/digests", Access.MANAGER);
        put("POST /api/digests/1/send", Access.MANAGER);

        // --- Base de connaissances : ecriture ADMIN, lecture AGENT ------------
        // La dissymetrie est voulue : consulter ne modifie rien, et un agent qui verifie une
        // citation de brouillon ne doit pas se voir refuser l'acces a sa source.
        put("POST /api/kb/documents", Access.ADMIN);
        put("GET /api/kb/documents", Access.ADMIN);
        // Trouvee par le garde-fou de peremption, pas par ma relecture : c'est exactement ce qu'il
        // existe pour attraper. Une suppression de document non auditee serait le pire oubli
        // possible de cette matrice — elle retire du contenu que les brouillons citent.
        put("DELETE /api/kb/documents/1", Access.ADMIN);
        put("POST /api/kb/reindex", Access.ADMIN);
        put("POST /api/kb/search", Access.AGENT);

        // --- Administration ---------------------------------------------------
        put("POST /api/imports", Access.ADMIN);
        put("POST /api/imports/1/confirm", Access.ADMIN);
        put("GET /api/admin/analysis-recovery", Access.ADMIN);
        put("POST /api/admin/analysis-recovery/run", Access.ADMIN);
    }};

    @Autowired
    TestRestTemplate rest;

    /**
     * {@code @Qualifier} obligatoire : Actuator declare son propre
     * {@code controllerEndpointHandlerMapping}, du meme type. Sans le nom, l'injection est ambigue
     * — et prendre celui d'Actuator ferait enumerer les endpoints de supervision au lieu de l'API.
     */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    /**
     * <b>Le garde-fou contre la peremption.</b>
     *
     * <p>Enumere les routes reellement exposees par Spring et les compare a la matrice. Sans ce
     * test, ajouter un controleur sans l'y declarer passerait inapercu : les cas de la matrice
     * continueraient de passer, et le nouvel endpoint ne serait jamais audite. C'est exactement le
     * mode de defaillance d'une checklist ecrite dans un document.
     *
     * <p>L'echec nomme les routes manquantes, avec le niveau a decider — l'auteur du controleur est
     * la bonne personne pour trancher, pas celui qui relira la matrice six mois plus tard.
     */
    @Test
    void everyExposedRouteIsDeclaredInTheMatrix() {
        Set<String> exposed = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<String> patterns = info.getPathPatternsCondition() == null
                    ? Set.of()
                    : info.getPathPatternsCondition().getPatternValues();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/api/")) {
                    continue; // actuator et gestionnaire d'erreur : hors perimetre de cette matrice
                }
                for (var method : info.getMethodsCondition().getMethods()) {
                    exposed.add(toPattern(method.name() + " " + pattern));
                }
            }
        }

        Set<String> declared = MATRIX.keySet().stream()
                .map(RbacMatrixTest::toPattern)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(exposed)
                .as("Routes exposees par Spring mais absentes de MATRIX. Ajoutez-les avec leur "
                        + "niveau d'acces attendu : un endpoint non declare n'est jamais audite.")
                .isSubsetOf(declared);

        assertThat(declared)
                .as("Routes declarees dans MATRIX mais qui n'existent plus. Une matrice qui decrit "
                        + "des routes disparues finit par ne plus decrire les vraies.")
                .isSubsetOf(exposed);
    }

    /**
     * Un cas de test par (route, role), genere depuis la matrice.
     *
     * <p>{@code @TestFactory} plutot qu'une boucle dans un seul {@code @Test} : un echec nomme alors
     * la route et le role fautifs dans le rapport de la CI, au lieu d'un unique test rouge dont il
     * faut lire la trace pour savoir ce qui a cede.
     */
    @TestFactory
    List<DynamicTest> theMatrixHolds() {
        String anonymous = null;
        String agent = tokenFor("AGENT");
        String manager = tokenFor("MANAGER");
        String admin = tokenFor("ADMIN");

        return MATRIX.entrySet().stream()
                .flatMap(entry -> List.of(
                        check(entry, "anonyme", anonymous),
                        check(entry, "AGENT", agent),
                        check(entry, "MANAGER", manager),
                        check(entry, "ADMIN", admin)).stream())
                .toList();
    }

    private DynamicTest check(Map.Entry<String, Access> entry, String roleName, String token) {
        String route = entry.getKey();
        Access required = entry.getValue();
        boolean shouldPass = allows(required, roleName);

        return DynamicTest.dynamicTest(route + " — " + roleName, () -> {
            HttpStatusCode status = call(route, token);

            if (required == Access.PUBLIC_APP_AUTH) {
                // Le webhook s'authentifie lui-meme : il refuse tout le monde faute de signature,
                // jeton JWT ou pas. Ce qu'on verifie ici est donc l'inverse de l'intuition — que
                // Spring Security ne s'en melange pas, c'est-a-dire qu'aucun role ne change la
                // reponse. Un 403 signalerait qu'une regle d'URL est venue s'interposer.
                assertThat(status)
                        .as("%s ne doit pas dependre du role : son authentification lui est propre",
                                route)
                        .isNotEqualTo(HttpStatus.FORBIDDEN);
                return;
            }

            if (shouldPass) {
                // On n'exige pas un 2xx : la ressource « 1 » n'existe pas, le service IA est
                // injoignable, le corps est vide. Ce qui compte est d'avoir franchi le controle.
                assertThat(status)
                        .as("%s devrait etre accessible a %s", route, roleName)
                        .isNotEqualTo(HttpStatus.FORBIDDEN)
                        .isNotEqualTo(HttpStatus.UNAUTHORIZED);
            } else if (token == null) {
                assertThat(status)
                        .as("%s sans jeton doit etre refuse", route)
                        .isEqualTo(HttpStatus.UNAUTHORIZED);
            } else {
                assertThat(status)
                        .as("%s ne doit pas etre accessible a %s", route, roleName)
                        .isEqualTo(HttpStatus.FORBIDDEN);
            }
        });
    }

    /** Hierarchie ADMIN &gt; MANAGER &gt; AGENT, telle que declaree dans la configuration Spring. */
    private static boolean allows(Access required, String roleName) {
        if (required == Access.PUBLIC) {
            return true;
        }
        if ("anonyme".equals(roleName)) {
            return false;
        }
        return switch (required) {
            case AUTHENTICATED, AGENT -> true;
            case MANAGER -> !"AGENT".equals(roleName);
            case ADMIN -> "ADMIN".equals(roleName);
            default -> false;
        };
    }

    private HttpStatusCode call(String route, String token) {
        String[] parts = route.split(" ", 2);
        HttpMethod method = HttpMethod.valueOf(parts[0]);

        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }

        HttpEntity<?> request;
        if (MULTIPART.contains(route)) {
            // Un corps JSON provoquerait un 415 avant toute verification de role. Le fichier est
            // minuscule et son contenu sans importance : ce test ne mesure pas l'import.
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            var form = new org.springframework.util.LinkedMultiValueMap<String, Object>();
            form.add("file", new org.springframework.core.io.ByteArrayResource(
                    "reference,sujet\nX-1,Sujet\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                @Override
                public String getFilename() {
                    return "sonde.csv";
                }
            });
            request = new HttpEntity<>(form, headers);
        } else {
            headers.setContentType(MediaType.APPLICATION_JSON);
            // Corps **valide** quand la route en exige un : Spring MVC valide les arguments avant
            // d'invoquer la methode, donc avant `@PreAuthorize`. Un corps invalide donnerait 400
            // pour tout le monde, et l'autorisation ne serait jamais atteinte.
            request = new HttpEntity<>(BODIES.getOrDefault(route, "{}"), headers);
        }

        ResponseEntity<String> response = rest.exchange(parts[1], method, request, String.class);
        return response.getStatusCode();
    }

    /** « POST /api/tickets/1/draft » -&gt; « POST /api/tickets/{id}/draft ». */
    private static String toPattern(String route) {
        return route.replaceAll("\\{[^}]+\\}", "{}").replaceAll("/\\d+", "/{}");
    }

    private String tokenFor(String role) {
        if ("ADMIN".equals(role)) {
            return login("admin@supportiq.local", "admin1234");
        }
        String email = role.toLowerCase() + "-rbac@supportiq.local";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login("admin@supportiq.local", "admin1234"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.postForEntity("/api/auth/register",
                new HttpEntity<>(Map.of("email", email, "password", "secret1234",
                        "fullName", "Audit " + role, "role", role), headers),
                Map.class);
        return login(email, "secret1234");
    }

    private String login(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("email", email, "password", password), headers), Map.class);
        return (String) response.getBody().get("accessToken");
    }
}
