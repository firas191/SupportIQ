package com.supportiq.backend.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/** Ecrit un ProblemDetail (RFC 7807) directement dans la reponse, cote filtre de securite. */
final class ProblemDetailWriter {

    private ProblemDetailWriter() {
    }

    static void write(HttpServletResponse response, ObjectMapper mapper, HttpStatus status,
            String title, String detail, String typeSuffix) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create("urn:supportiq:error:" + typeSuffix));
        pd.setProperty("timestamp", Instant.now());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getWriter(), pd);
    }
}
