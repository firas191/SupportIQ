package com.supportiq.backend.imports;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Contrat commun des parseurs de fichiers structures. Le coeur est stream() : lecture ligne a
 * ligne sans chargement complet. parse() (apercu + rapport d'erreurs) en est un cas particulier.
 */
public interface StructuredFileParser {

    boolean supports(FileType type);

    /** @param charset ignore pour les formats binaires (XLSX). */
    void stream(InputStream input, Charset charset, RowHandler handler) throws IOException;

    /** Apercu borne + comptage + erreurs structurelles, construit par-dessus stream(). */
    default ParsedFile parse(InputStream input, Charset charset) throws IOException {
        RowCollectorHandler handler = new RowCollectorHandler();
        stream(input, charset, handler);
        return handler.toParsedFile();
    }
}
