package com.supportiq.backend.tickets;

/** Cycle de vie d'un ticket (rapport §4). A l'insertion : NEW ; ANALYZED apres le triage IA (S3). */
public enum TicketStatus {
    NEW,
    ANALYZED,
    IN_PROGRESS,
    RESOLVED,
    MERGED
}
