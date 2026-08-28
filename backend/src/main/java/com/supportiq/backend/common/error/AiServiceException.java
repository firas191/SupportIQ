package com.supportiq.backend.common.error;

/**
 * Echec d'un appel au service IA, avec le statut amont preserve.
 *
 * <p><b>Pourquoi elle apparait maintenant.</b> Le projet compte quatre exceptions de forme
 * identique — {@code KbException}, {@code DraftException}, {@code InsightException},
 * {@code DigestException} — chacune ne portant qu'un {@code int status} et un message. Au S5-J4
 * j'avais ecrit que la remontee se ferait « au troisieme client » ; elle ne s'est pas faite, et le
 * S7-J1 en amenait un cinquieme. Ecrire une cinquieme copie aurait transforme une dette reconnue en
 * habitude.
 *
 * <p>Elle est donc introduite ici et utilisee par le module {@code topics}. Les quatre existantes
 * <b>ne sont pas migrees dans le meme lot</b> : elles fonctionnent, elles sont couvertes par des
 * tests d'integration, et melanger un remaniement transverse a la livraison d'un jour est
 * exactement ce qui casse un jour de planning. La migration est notee comme dette d'avant
 * soutenance — et elle est desormais triviale, puisque la destination existe.
 *
 * <p>Deux champs plutot qu'un : le {@code title} et le {@code slug} font qu'un ProblemDetail reste
 * lisible cote client. Sans eux, tous les echecs du service IA se ressembleraient, et l'interface
 * ne pourrait plus dire <i>quelle</i> fonction est indisponible.
 */
public class AiServiceException extends RuntimeException {

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
