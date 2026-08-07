package com.supportiq.backend.drafts;

/** Transition de statut impossible (brouillon deja tranche, abstention qu'on tente de valider). */
public class DraftStateException extends RuntimeException {

    public DraftStateException(String message) {
        super(message);
    }
}
