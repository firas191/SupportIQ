package com.supportiq.backend.knowledge;

import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Base de connaissances — orchestration (S5-J1).
 *
 * <p>Repartition assumee :
 * <ul>
 *   <li><b>lecture</b> (liste des documents) : SQL direct, aucune dependance au service IA ;</li>
 *   <li><b>ecriture</b> (indexation, re-indexation, suppression) : deleguee au service IA, qui seul
 *       possede le modele d'embeddings.</li>
 * </ul>
 *
 * <p>Les garde-fous d'entree sont ici et non cote IA : un fichier vide ou une extension inconnue
 * doit etre refuse **avant** de traverser le reseau. Le plan de controle valide, le plan de calcul
 * calcule.
 */
@Service
public class KbService {

    /** Formats acceptes au J1. Les formats bureautiques et l'OCR arrivent en S7 (rapport §5.4). */
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".md", ".markdown", ".txt", ".pdf");
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final KbRepository repository;
    private final KbClient client;

    public KbService(KbRepository repository, KbClient client) {
        this.repository = repository;
        this.client = client;
    }

    public List<KbDocumentResponse> documents() {
        return repository.listDocuments();
    }

    public int totalChunks() {
        return repository.countChunks();
    }

    public KbIngestResponse ingest(MultipartFile file) {
        String filename = sanitize(file.getOriginalFilename());

        if (file.isEmpty()) {
            throw new KbException(HttpStatus.BAD_REQUEST.value(), "Le fichier est vide");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new KbException(HttpStatus.PAYLOAD_TOO_LARGE.value(), "Fichier trop volumineux (10 Mo maximum)");
        }
        if (ALLOWED_EXTENSIONS.stream().noneMatch(ext -> filename.toLowerCase(Locale.ROOT).endsWith(ext))) {
            throw new KbException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                    "Format non pris en charge (attendus : Markdown, texte ou PDF)");
        }

        try {
            return client.ingest(filename, file.getBytes());
        } catch (java.io.IOException e) {
            throw new KbException(HttpStatus.BAD_REQUEST.value(), "Le fichier n'a pas pu etre lu");
        }
    }

    public List<KbChunkResponse> search(KbSearchRequest request) {
        return client.search(request.question().strip(), request.safeK());
    }

    public int reindex(boolean force) {
        return client.reindex(force);
    }

    public int delete(String source) {
        return client.delete(sanitize(source));
    }

    /**
     * Neutralise le nom de fichier.
     *
     * <p>Le nom fourni par le client sert de cle d'identification du document et se retrouve dans
     * une URL de suppression. On retire donc toute composante de chemin ({@code ../}, separateurs)
     * avant de s'en servir : sans cela, un nom construit a la main pourrait viser un autre document
     * que celui affiche.
     */
    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new KbException(HttpStatus.BAD_REQUEST.value(), "Nom de fichier manquant");
        }
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new KbException(HttpStatus.BAD_REQUEST.value(), "Nom de fichier invalide");
        }
        return name.length() > 300 ? name.substring(0, 300) : name;
    }
}
