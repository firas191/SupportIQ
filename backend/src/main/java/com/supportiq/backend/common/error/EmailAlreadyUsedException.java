package com.supportiq.backend.common.error;

/** Email deja pris a la creation d'un compte -> 409 Conflict. */
public class EmailAlreadyUsedException extends RuntimeException {

    public EmailAlreadyUsedException(String email) {
        super("Un compte existe deja pour l'email : " + email);
    }
}
