package com.supportiq.backend.drafts;

/**
 * Demande de brouillon.
 *
 * <p>Le ton est le seul reglage expose (rapport §5.2). Deux registres suffisent : le formel couvre
 * le support courant, l'empathique les reclamations et les clients mecontents. En offrir dix
 * donnerait l'illusion du controle sans changer la substance de la reponse.
 */
public record GenerateDraftRequest(String tone) {

    public GenerateDraftRequest {
        if (tone == null || tone.isBlank()) {
            tone = "formal";
        }
    }
}
