package com.supportiq.backend.tickets;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /** Refs deja presentes parmi celles fournies : sert a dedupliquer a l'insertion (idempotence). */
    @Query("select t.externalRef from Ticket t where t.externalRef in :refs")
    List<String> findExistingExternalRefs(@Param("refs") Collection<String> refs);
}
