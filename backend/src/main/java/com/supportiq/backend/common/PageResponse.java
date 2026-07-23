package com.supportiq.backend.common;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Enveloppe de pagination stable pour l'API. On evite de serialiser directement {@code Page}/
 * {@code PageImpl} de Spring Data (structure JSON non garantie ; Boot 3 emet d'ailleurs un warning).
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
