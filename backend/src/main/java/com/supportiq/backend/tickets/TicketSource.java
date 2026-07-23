package com.supportiq.backend.tickets;

/** Origine d'un ticket (rapport §4). Les imports de fichiers produisent des tickets FILE. */
public enum TicketSource {
    FILE,
    WEBHOOK,
    EMAIL,
    MANUAL
}
