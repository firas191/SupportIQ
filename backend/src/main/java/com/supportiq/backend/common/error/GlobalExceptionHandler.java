package com.supportiq.backend.common.error;

import com.supportiq.backend.imports.FileParseException;
import com.supportiq.backend.imports.ImportStateException;
import com.supportiq.backend.imports.MappingValidationException;
import com.supportiq.backend.imports.UnsupportedFileTypeException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
