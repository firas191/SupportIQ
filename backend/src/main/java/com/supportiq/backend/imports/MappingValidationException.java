package com.supportiq.backend.imports;

/** Mapping de colonnes invalide (ex. 'subject' non mappe) -> 400. */
public class MappingValidationException extends RuntimeException {

    public MappingValidationException(String message) {
        super(message);
    }
}
