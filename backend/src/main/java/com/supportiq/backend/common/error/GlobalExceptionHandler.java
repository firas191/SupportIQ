package com.supportiq.backend.common.error;

import com.supportiq.backend.imports.FileParseException;
import com.supportiq.backend.drafts.DraftStateException;
import com.supportiq.backend.imports.ImportStateException;
import com.supportiq.backend.imports.MappingValidationException;
import com.supportiq.backend.imports.UnsupportedFileTypeException;
import com.supportiq.backend.tickets.TicketStateException;
import com.supportiq.backend.webhook.WebhookAuthException;
import com.supportiq.backend.webhook.WebhookPayloadException;
import com.supportiq.backend.webhook.WebhookRateLimitException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Point unique de traduction des exceptions en reponses RFC 7807 (ProblemDetail).
 * Regle senior : aucune stacktrace exposee au client, aucune map d'erreur ad hoc dans les controleurs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BASE_TYPE = "urn:supportiq:error:";

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Ressource introuvable", ex.getMessage(), "not-found");
    }

    @ExceptionHandler(EmailAlreadyUsedException.class)
    public ProblemDetail handleEmailTaken(EmailAlreadyUsedException ex) {
        return problem(HttpStatus.CONFLICT, "Conflit", ex.getMessage(), "email-already-used");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Requete invalide",
                "Un ou plusieurs champs sont invalides.", "validation");
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        pd.setProperty("errors", fieldErrors);
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Requete invalide",
                "Corps de requete absent ou JSON malforme.", "malformed-body");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        // Ex. valeur de filtre invalide sur GET /api/tickets (?status=, ?source=).
        return problem(HttpStatus.BAD_REQUEST, "Parametre invalide", ex.getMessage(), "bad-parameter");
    }

    // --- Imports (S2) -----------------------------------------------------------

    @ExceptionHandler(UnsupportedFileTypeException.class)
    public ProblemDetail handleUnsupportedFile(UnsupportedFileTypeException ex) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Format non supporte", ex.getMessage(),
                "unsupported-file-type");
    }

    @ExceptionHandler(FileParseException.class)
    public ProblemDetail handleParse(FileParseException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Fichier illisible", ex.getMessage(), "file-parse");
    }

    @ExceptionHandler(MappingValidationException.class)
    public ProblemDetail handleMapping(MappingValidationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Mapping invalide", ex.getMessage(), "mapping-invalid");
    }

    @ExceptionHandler(ImportStateException.class)
    public ProblemDetail handleImportState(ImportStateException ex) {
        return problem(HttpStatus.CONFLICT, "Etat d'import invalide", ex.getMessage(), "import-state");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleTooLarge(MaxUploadSizeExceededException ex) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Fichier trop volumineux",
                "La taille du fichier depasse la limite autorisee.", "file-too-large");
    }

    // --- Tickets (S4-J4) --------------------------------------------------------

    @ExceptionHandler(TicketStateException.class)
    public ProblemDetail handleTicketState(TicketStateException ex) {
        // Ex. fusion d'un ticket deja fusionne, correction d'un ticket pas encore analyse.
        return problem(HttpStatus.CONFLICT, "Etat de ticket invalide", ex.getMessage(), "ticket-state");
    }

    // --- Brouillons de reponse (S5-J4) ------------------------------------------

    @ExceptionHandler(DraftStateException.class)
    public ProblemDetail handleDraftState(DraftStateException ex) {
        // Ex. revue d'un brouillon deja tranche, validation d'une abstention.
        //
        // **Reste une exception a part**, et volontairement : ce n'est pas un echec d'appel au
        // service IA mais une **regle metier** — une transition d'etat interdite. Elle repond
        // toujours 409, quel que soit l'etat du service IA, et la fusionner avec les echecs
        // techniques ferait disparaitre cette distinction. Meme raison pour TicketStateException et
        // ImportStateException plus haut.
        return problem(HttpStatus.CONFLICT, "Etat de brouillon invalide", ex.getMessage(), "draft-state");
    }

    // --- Appels au service IA (S7-J1, unifie au S8-J1) ---------------------------

    /**
     * Gestionnaire unique des echecs cote service IA.
     *
     * <p>Il en remplace quatre — {@code KbException}, {@code DraftException},
     * {@code InsightException}, {@code DigestException} — dont les corps etaient identiques au
     * couple (titre, slug) pres. Ce couple vit desormais dans les fabriques d'
     * {@link AiServiceException}, donc les ProblemDetail produits sont inchanges : memes statuts,
     * memes titres, memes slugs. Aucun client n'a a s'en apercevoir, et c'est bien la preuve que les
     * quatre classes n'apportaient rien.
     *
     * <p>Le statut amont est preserve, pour la raison qui valait deja pour chacune des quatre :
     * « fonction indisponible » (503), « format refuse » (415) et « demande hors perimetre » (422)
     * n'appellent pas la meme reaction de l'utilisateur. Les aplatir en 500 les rendrait
     * indiscernables — un refus legitime passerait pour une panne.
     */
    @ExceptionHandler(AiServiceException.class)
    public ProblemDetail handleAiService(AiServiceException ex) {
        HttpStatus status = HttpStatus.resolve(ex.status());
        return problem(
                status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status,
                ex.title(),
                ex.getMessage(),
                ex.slug());
    }

    // --- Webhook (S2-J4) --------------------------------------------------------

    @ExceptionHandler(WebhookAuthException.class)
    public ProblemDetail handleWebhookAuth(WebhookAuthException ex) {
        // 401 volontairement generique : ne pas indiquer si c'est la cle ou la signature qui a echoue.
        return problem(HttpStatus.UNAUTHORIZED, "Webhook non authentifie",
                "Cle API ou signature invalide.", "webhook-unauthorized");
    }

    @ExceptionHandler(WebhookPayloadException.class)
    public ProblemDetail handleWebhookPayload(WebhookPayloadException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Charge utile invalide", ex.getMessage(), "webhook-payload");
    }

    @ExceptionHandler(WebhookRateLimitException.class)
    public ProblemDetail handleWebhookRateLimit(WebhookRateLimitException ex) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "Trop de requetes", ex.getMessage(), "rate-limit");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleIntegrity(DataIntegrityViolationException ex) {
        // Ex. course sur uq_tickets_external_ref (deux appels concurrents avec la meme ref).
        return problem(HttpStatus.CONFLICT, "Conflit de donnees",
                "La ressource existe deja ou viole une contrainte d'unicite.", "data-integrity");
    }

    // --- Securite ---------------------------------------------------------------

    @ExceptionHandler({BadCredentialsException.class, InvalidTokenException.class, AuthenticationException.class})
    public ProblemDetail handleAuth(RuntimeException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentification refusee",
                "Identifiants ou jeton invalides.", "unauthorized");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Acces refuse",
                "Vous n'avez pas les droits necessaires.", "forbidden");
    }

    // --- Chemin inconnu ---------------------------------------------------------

    /**
     * URL non mappee : <b>404, pas 500</b> (correctif S7-J5).
     *
     * <p>Depuis Spring Boot 3.2, une requete qui ne correspond a aucun controleur ni a aucune
     * ressource statique leve {@code NoResourceFoundException}. Sans ce gestionnaire, elle tombait
     * dans l'attrape-tout {@code Exception.class} ci-dessous et ressortait en <b>500 « Erreur
     * interne »</b>, avec une pile complete en {@code log.error}.
     *
     * <p>Deux consequences, et la seconde est la plus couteuse :
     * <ul>
     *   <li>une faute de frappe dans une URL devenait indiscernable d'une panne du serveur, cote
     *       client comme cote supervision ;</li>
     *   <li>n'importe quel robot sondant des chemins au hasard remplissait les journaux d'erreurs
     *       qui n'en sont pas — et <i>des erreurs qui n'indiquent aucun defaut apprennent a ignorer
     *       les journaux d'erreurs</i>.</li>
     * </ul>
     *
     * <p>Trouve par un test qui verifiait la <b>disparition</b> d'une route (le bouchon
     * {@code /api/dashboard/alerts}, retire au S7-J2). Il attendait 404, a recu 500 : le defaut
     * existait depuis la creation de cet attrape-tout, et aucun test ne demandait jamais une URL
     * inexistante.
     *
     * <p>Les <b>deux</b> exceptions sont couvertes parce qu'elles decrivent la meme situation selon
     * la configuration : {@code NoResourceFoundException} quand le gestionnaire de ressources
     * statiques est actif (defaut depuis Boot 3.2), {@code NoHandlerFoundException} sinon. Parier
     * sur l'une des deux ferait dependre un code de reponse d'un reglage sans rapport.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ProblemDetail handleNotFound(Exception ex) {
        return problem(HttpStatus.NOT_FOUND, "Ressource introuvable",
                "Aucune ressource ne correspond a cette adresse.", "not-found");
    }

    /**
     * Attrape-tout, volontairement <b>en dernier</b>. Spring choisit toujours le gestionnaire le
     * plus specifique : tout ce qui arrive ici est, par construction, une exception que personne
     * n'avait prevue — d'ou la trace complete, qui a du sens pour celles-la et pour elles seules.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Erreur non geree", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne",
                "Une erreur inattendue est survenue.", "internal");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String typeSuffix) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create(BASE_TYPE + typeSuffix));
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}
