package com.supportiq.backend.imports;

/** Operation invalide vu l'etat de l'import (ex. confirm d'un import deja traite) -> 409. */
public class ImportStateException extends RuntimeException {

    public ImportStateException(String message) {
        super(message);
    }
}
