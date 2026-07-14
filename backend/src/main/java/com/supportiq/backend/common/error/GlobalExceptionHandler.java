package com.supportiq.backend.common.error;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    // --- Securite : identifiants et autorisations -------------------------------

    /** Mauvais identifiants au login, ou refresh/token invalide -> 401 (message volontairement flou). */
    @ExceptionHandler({BadCredentialsException.class, InvalidTokenException.class, AuthenticationException.class})
    public ProblemDetail handleAuth(RuntimeException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentification refusee",
                "Identifiants ou jeton invalides.", "unauthorized");
    }

    /** Role insuffisant (@PreAuthorize) -> 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Acces refuse",
                "Vous n'avez pas les droits necessaires.", "forbidden");
    }

    /** Filet de securite : toute exception non mappee -> 500 generique, detail logge cote serveur. */
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
