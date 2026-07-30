package com.supportiq.backend.tickets;

/** Operation incompatible avec l'etat du ticket (deja fusionne, pas encore analyse...) -> 409. */
public class TicketStateException extends RuntimeException {

    public TicketStateException(String message) {
        super(message);
    }
}
