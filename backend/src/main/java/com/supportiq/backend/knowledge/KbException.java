package com.supportiq.backend.knowledge;

/**
 * Echec d'une operation sur la base de connaissances (S5-J1).
 *
 * <p>Porte le statut a renvoyer au client : le service IA distingue deja un format refuse (415)
 * d'une panne (503), et cette information ne doit pas se perdre en traversant Spring.
 */
public class KbException extends RuntimeException {

    private final int status;

    public KbException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
