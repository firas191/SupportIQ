package com.supportiq.backend.imports;

/** Bilan du confirm : tickets inseres, doublons ignores, statut final de l'import. */
public record ConfirmImportResponse(Long importId, int inserted, int skipped, String status) {
}
