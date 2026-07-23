package com.supportiq.backend.imports;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Corps du confirm : association champ ticket -> nom de colonne du fichier.
 * Cles supportees : externalRef, customerEmail, subject, body, createdAt, language ('subject' requis).
 */
public record ConfirmImportRequest(@NotNull Map<String, String> mapping) {
}
