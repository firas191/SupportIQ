package com.supportiq.backend.common.error;

/**
 * Exception metier : ressource demandee inexistante -> 404.
 * Les exceptions metier portent leur semantique ; le mapping HTTP se fait au seul endroit
 * qu'est le GlobalExceptionHandler (pas de ResponseStatusException dispersee dans le code).
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
