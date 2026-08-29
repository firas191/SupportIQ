package com.supportiq.backend.common.error;

/**
 * Echec d'un appel au service IA, avec le statut amont preserve.
 *
 * <p><b>Pourquoi elle existe.</b> Le projet comptait quatre exceptions de forme identique —
 * {@code KbException}, {@code DraftException}, {@code InsightException}, {@code DigestException} —
 * chacune ne portant qu'un {@code int status} et un message, et chacune avec son
 * {@code @ExceptionHandler} recopie a l'identique. Au S5-J4 j'avais ecrit que la remontee se ferait
 * « au troisieme client » ; elle ne s'est pas faite, et le S7-J1 en amenait un cinquieme. Ecrire une
 * cinquieme copie aurait transforme une dette reconnue en habitude.
 *
 * <p><b>Les quatre sont desormais migrees ici</b> (S8-J1), avec leurs quatre gestionnaires reduits a
 * un seul. Aucun comportement ne change : les statuts, les titres et les slugs des ProblemDetail
 * sont identiques a ceux d'avant, et c'est justement ce qui rend le remaniement sur.
 *
 * <p><b>Trois champs plutot qu'un.</b> Le {@code title} et le {@code slug} font qu'un ProblemDetail
 * reste lisible cote client. Sans eux, tous les echecs du service IA se ressembleraient, et
 * l'interface ne pourrait plus dire <i>quelle</i> fonction est indisponible — c'etait la seule chose
 * que les quatre classes apportaient reellement.
 *
 * <p><b>Et des fabriques par domaine plutot qu'un constructeur nu.</b> Fusionner en imposant
 * {@code new AiServiceException(503, "Base de connaissances", "knowledge-base", msg)} a chacun des
 * trente-quatre sites d'appel aurait alourdi chaque {@code throw} de deux chaines litterales, et
 * surtout disperse en trente-quatre exemplaires le libelle qu'on venait de centraliser. Les
 * fabriques gardent l'ergonomie d'avant — {@code AiServiceException.kb(400, msg)} — avec une seule
 * definition de chaque libelle.
 */
public class AiServiceException extends RuntimeException {

    /** Base de connaissances (S5-J1). */
    public static AiServiceException kb(int status, String message) {
        return new AiServiceException(status, "Base de connaissances", "knowledge-base", message);
    }

    /** Assistant de redaction (S5-J4). */
    public static AiServiceException draft(int status, String message) {
        return new AiServiceException(status, "Assistant de redaction", "draft-generation", message);
    }

    /** Agent Insight, text-to-SQL (S6-J3). */
    public static AiServiceException insight(int status, String message) {
        return new AiServiceException(status, "Assistant d'analyse", "insight", message);
    }

    /** Synthese hebdomadaire (S6-J4). */
    public static AiServiceException digest(int status, String message) {
        return new AiServiceException(status, "Synthese hebdomadaire", "digest", message);
    }

    private final int status;
    private final String title;
    private final String slug;

    public AiServiceException(int status, String title, String slug, String message) {
        super(message);
        this.status = status;
        this.title = title;
        this.slug = slug;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String slug() {
        return slug;
    }
}
