package com.supportiq.backend.drafts;

/**
 * Echec de generation d'un brouillon, avec le statut a renvoyer au client.
 *
 * <p>Meme forme que {@link com.supportiq.backend.knowledge.KbException} — duplication assumee. Deux
 * petites exceptions au proprietaire clair valent mieux qu'une abstraction posee sur deux cas ; si
 * un troisieme client du service IA apparait (S6 : agent Insight), les trois seront remontees dans
 * {@code common/error}. C'est la regle de trois, pas de la paresse.
 */
public class DraftException extends RuntimeException {

    private final int status;

    public DraftException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
