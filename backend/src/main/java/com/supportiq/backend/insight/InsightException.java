package com.supportiq.backend.insight;

/**
 * Echec d'une question Insight, avec le statut a renvoyer au client.
 *
 * <p>Troisieme exception de cette forme apres {@code KbException} et {@code DraftException}. La
 * regle de trois est atteinte : elles devraient etre remontees dans {@code common/error} en une
 * seule {@code AiServiceException}. Ce n'est pas fait aujourd'hui parce que la refonte toucherait
 * trois modules deja verifies au milieu d'une journee dediee a l'interface — et melanger un
 * remaniement transverse a une livraison fonctionnelle est la meilleure facon de ne savoir lequel
 * des deux a casse quoi. C'est note comme dette, avec son declencheur.
 */
public class InsightException extends RuntimeException {

    private final int status;

    public InsightException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
