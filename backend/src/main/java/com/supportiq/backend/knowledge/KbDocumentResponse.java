package com.supportiq.backend.knowledge;

import java.time.Instant;

/**
 * Un document de la base de connaissances, vu par l'ecran d'administration (S5-J1).
 *
 * <p>L'unite exposee est le **document**, pas le fragment : un administrateur raisonne en « j'ai
 * charge la FAQ facturation », pas en « j'ai charge 5 fragments ». L'agregation se fait en SQL.
 *
 * @param chunks  nombre total de fragments issus du decoupage semantique
 * @param indexed nombre de fragments effectivement vectorises — un ecart avec {@code chunks} signale
 *                des fragments invisibles a la recherche, donc une re-indexation a lancer
 */
public record KbDocumentResponse(
        String source,
        String title,
        int chunks,
        int indexed,
        Instant updatedAt) {

    /** Vrai quand tous les fragments sont interrogeables. */
    public boolean isFullyIndexed() {
        return chunks > 0 && chunks == indexed;
    }
}
