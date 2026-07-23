package com.supportiq.backend.tickets;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Ticket de support (rapport §4). import_id et merged_into_id sont mappes en Long (pas en
 * associations JPA) pour eviter un couplage inter-modules et des jointures inutiles au J2.
 */
@Entity
@Table(name = "tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_ref", length = 255)
    private String externalRef;

    @Column(name = "import_id")
    private Long importId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TicketSource source;

    @Column(name = "customer_email", length = 320)
    private String customerEmail;

    @Column
    private String subject;

    @Column
    private String body;

    @Column(length = 2)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TicketStatus status;

    @Column(name = "sla_due_at")
    private Instant slaDueAt;

    @Column(name = "merged_into_id")
    private Long mergedIntoId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
