package com.supportiq.backend.imports;

import com.supportiq.backend.auth.User;
import com.supportiq.backend.auth.UserRepository;
import com.supportiq.backend.common.error.ResourceNotFoundException;
import com.supportiq.backend.messaging.TicketsPersistedEvent;
import com.supportiq.backend.tickets.TicketRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Import structure (S2). Upload (J1) : detection type+encodage, parsing streaming pour l'apercu,
 * stockage du fichier, ligne `imports` AWAITING_VALIDATION. Confirm (J2/J3) : re-parse du fichier
 * stocke avec le mapping, insertion des tickets par lots (dedup), puis publication asynchrone
 * `ticket.created` **apres commit** (J3) pour l'analyse IA.
 */
@Service
public class ImportService {

    private static final int HEAD_SIZE = 64 * 1024;
    private static final List<String> SUPPORTED_FIELDS =
            List.of("externalRef", "customerEmail", "subject", "body", "createdAt", "language");

    private final List<StructuredFileParser> parsers;
    private final FileTypeDetector fileTypeDetector;
    private final CharsetDetector charsetDetector;
    private final ImportStorage storage;
    private final ImportJobRepository imports;
    private final UserRepository users;
    private final TicketRepository tickets;
    private final ApplicationEventPublisher eventPublisher;

    public ImportService(List<StructuredFileParser> parsers, FileTypeDetector fileTypeDetector,
            CharsetDetector charsetDetector, ImportStorage storage, ImportJobRepository imports,
            UserRepository users, TicketRepository tickets, ApplicationEventPublisher eventPublisher) {
        this.parsers = parsers;
        this.fileTypeDetector = fileTypeDetector;
        this.charsetDetector = charsetDetector;
        this.storage = storage;
        this.imports = imports;
        this.users = users;
        this.tickets = tickets;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ImportPreviewResponse importFile(MultipartFile file, String uploaderEmail) {
        User uploader = users.findByEmail(uploaderEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur courant introuvable"));
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "sans-nom";

        Path tmp = null;
        try {
            tmp = Files.createTempFile("supportiq-import-", ".tmp");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            byte[] head = readHead(tmp);
            FileType type = fileTypeDetector.detect(filename, head);
            Charset charset = charsetDetector.detect(head);
            StructuredFileParser parser = parserFor(type, filename);

            ParsedFile parsed;
            try (InputStream in = Files.newInputStream(tmp)) {
                parsed = parser.parse(in, charset);
            }

            ImportJob job = imports.save(ImportJob.builder()
                    .filename(filename)
                    .fileType(type)
                    .uploadedBy(uploader)
                    .rowCount(parsed.totalRows())
                    .status(ImportStatus.AWAITING_VALIDATION)
                    .build());

            storage.store(job.getId(), tmp);
            tmp = null; // deplace vers le stockage definitif
            return ImportPreviewResponse.from(job, charset, parsed);
        } catch (IOException e) {
            throw new FileParseException("Fichier illisible : " + e.getMessage(), e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    @Transactional
    public ConfirmImportResponse confirm(long importId, Map<String, String> requestedMapping) {
        ImportJob job = imports.findById(importId)
                .orElseThrow(() -> new ResourceNotFoundException("Import introuvable : " + importId));
        if (job.getStatus() != ImportStatus.AWAITING_VALIDATION) {
            throw new ImportStateException("L'import " + importId
                    + " n'est pas en attente de validation (statut " + job.getStatus() + ").");
        }
        Map<String, String> mapping = sanitize(requestedMapping);
        if (!mapping.containsKey("subject")) {
            throw new MappingValidationException("Le champ 'subject' doit etre mappe a une colonne.");
        }

        StructuredFileParser parser = parserFor(job.getFileType(), job.getFilename());
        TicketInserter inserter = new TicketInserter(mapping, job.getId(), tickets);
        try {
            Charset charset;
            try (InputStream in = storage.open(importId)) {
                charset = charsetDetector.detect(in.readNBytes(HEAD_SIZE));
            }
            try (InputStream in = storage.open(importId)) {
                parser.stream(in, charset, inserter);
            }
            inserter.finish();
        } catch (IOException e) {
            throw new FileParseException("Relecture du fichier impossible : " + e.getMessage(), e);
        }

        job.setColumnMapping(mapping);
        job.setStatus(ImportStatus.DONE);
        imports.save(job);

        // Publication asynchrone APRES commit (voir TicketEventPublisher).
        if (!inserter.events().isEmpty()) {
            eventPublisher.publishEvent(new TicketsPersistedEvent(inserter.events()));
        }
        return new ConfirmImportResponse(importId, inserter.inserted(), inserter.skipped(),
                job.getStatus().name());
    }

    private Map<String, String> sanitize(Map<String, String> requested) {
        Map<String, String> clean = new LinkedHashMap<>();
        if (requested != null) {
            for (String field : SUPPORTED_FIELDS) {
                String column = requested.get(field);
                if (column != null && !column.isBlank()) {
                    clean.put(field, column.trim());
                }
            }
        }
        return clean;
    }

    private StructuredFileParser parserFor(FileType type, String filename) {
        return parsers.stream()
                .filter(p -> p.supports(type))
                .findFirst()
                .orElseThrow(() -> new UnsupportedFileTypeException(filename));
    }

    private byte[] readHead(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes(HEAD_SIZE);
        }
    }

    private void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
