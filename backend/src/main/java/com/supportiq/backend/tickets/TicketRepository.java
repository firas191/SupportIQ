package com.supportiq.backend.tickets;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    /** Refs deja presentes parmi celles fournies : sert a dedupliquer a l'insertion (idempotence). */
    @Query("select t.externalRef from Ticket t where t.externalRef in :refs")
    List<String> findExistingExternalRefs(@Param("refs") Collection<String> refs);

    /** Id du ticket portant cette ref (idempotence du webhook : renvoyer le ticket existant). */
    @Query("select t.id from Ticket t where t.externalRef = :ref")
    Optional<Long> findIdByExternalRef(@Param("ref") String ref);
}
