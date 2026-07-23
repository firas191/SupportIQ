package com.supportiq.backend.imports;

import com.supportiq.backend.messaging.TicketCreatedEvent;
import com.supportiq.backend.tickets.Ticket;
import com.supportiq.backend.tickets.TicketRepository;
import com.supportiq.backend.tickets.TicketSource;
import com.supportiq.backend.tickets.TicketStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RowHandler qui transforme chaque ligne en Ticket via le mapping et insere par lots.
 * Memoire bornee (lot de {@value #BATCH}) et deduplication par external_ref (idempotence).
 * Collecte aussi les evenements ticket.created (publies apres commit par ImportService).
 */
final class TicketInserter implements RowHandler {

    private static final int BATCH = 500;

    private final Map<String, String> mapping;      // champ ticket -> nom de colonne
    private final Long importId;
    private final TicketRepository tickets;
    private final Map<String, Integer> columnIndex = new HashMap<>();
    private final List<Ticket> batch = new ArrayList<>(BATCH);
    private final List<TicketCreatedEvent> events = new ArrayList<>();

    private int inserted = 0;
    private int skipped = 0;

    TicketInserter(Map<String, String> mapping, Long importId, TicketRepository tickets) {
        this.mapping = mapping;
        this.importId = importId;
        this.tickets = tickets;
    }

    @Override
    public void onHeaders(List<String> headers) {
        for (int i = 0; i < headers.size(); i++) {
            columnIndex.putIfAbsent(headers.get(i), i);
        }
    }

    @Override
    public void onRow(List<String> row, int lineNumber) {
        batch.add(buildTicket(row));
        if (batch.size() >= BATCH) {
            flush();
        }
    }

    void finish() {
        flush();
    }

    int inserted() {
        return inserted;
    }

    int skipped() {
        return skipped;
    }

    List<TicketCreatedEvent> events() {
        return events;
    }

    private void flush() {
        if (batch.isEmpty()) {
            return;
        }
        Set<String> refs = new HashSet<>();
        for (Ticket t : batch) {
            if (t.getExternalRef() != null) {
                refs.add(t.getExternalRef());
            }
        }
        Set<String> seen = refs.isEmpty()
                ? new HashSet<>()
                : new HashSet<>(tickets.findExistingExternalRefs(refs));

        List<Ticket> toInsert = new ArrayList<>(batch.size());
        for (Ticket t : batch) {
            String ref = t.getExternalRef();
            if (ref != null && !seen.add(ref)) {
                skipped++;               // deja en base ou doublon dans le lot
                continue;
            }
            toInsert.add(t);
        }
        tickets.saveAll(toInsert);
        for (Ticket t : toInsert) {
            events.add(new TicketCreatedEvent(t.getId(), t.getExternalRef(), t.getSubject(),
                    t.getBody(), t.getLanguage()));
        }
        inserted += toInsert.size();
        batch.clear();
    }

    private Ticket buildTicket(List<String> row) {
        return Ticket.builder()
                .importId(importId)
                .source(TicketSource.FILE)
                .status(TicketStatus.NEW)
                .externalRef(value(row, "externalRef"))
                .customerEmail(value(row, "customerEmail"))
                .subject(value(row, "subject"))
                .body(value(row, "body"))
                .language(normalizeLanguage(value(row, "language")))
                .createdAt(parseInstant(value(row, "createdAt")))
                .build();
    }

    private String value(List<String> row, String field) {
        String column = mapping.get(field);
        if (column == null) {
            return null;
        }
        Integer idx = columnIndex.get(column);
        if (idx == null || idx >= row.size()) {
            return null;
        }
        String v = row.get(idx);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private String normalizeLanguage(String v) {
        if (v == null) {
            return null;
        }
        String lang = v.toLowerCase(Locale.ROOT);
        return (lang.equals("fr") || lang.equals("en")) ? lang : null;
    }

    private Instant parseInstant(String v) {
        if (v == null) {
            return null; // @PrePersist posera now()
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException ignored) {
            // pas un instant ISO avec zone
        }
        try {
            return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
