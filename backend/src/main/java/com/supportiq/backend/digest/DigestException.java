package com.supportiq.backend.digest;

/**
 * Echec de generation ou d'envoi d'un digest.
 *
 * <p>Quatrieme exception de cette forme (Kb, Draft, Insight, Digest). La regle de trois est
 * depassee : elles doivent etre remontees dans {@code common/error} en une seule
 * {@code AiServiceException} portant un statut. C'est note comme dette dans CLAUDE.md avec les
 * autres travaux de consolidation d'avant-soutenance — pas fait ici pour ne pas melanger un
 * remaniement transverse a la livraison d'un jour.
 */
public class DigestException extends RuntimeException {

    private final int status;

    public DigestException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
