package com.supportiq.backend.imports;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stockage du fichier source d'un import. Le fichier est ecrit a l'upload et re-lu (streaming)
 * au moment du confirm pour inserer les tickets. Le dossier est configurable ; en prod, le
 * monter sur un volume Docker pour la persistance entre redemarrages.
 */
@Component
public class ImportStorage {

    private final Path dir;

    public ImportStorage(@Value("${app.imports.storage-dir}") String storageDir) throws IOException {
        this.dir = Path.of(storageDir);
        Files.createDirectories(this.dir);
    }

    public Path pathFor(long importId) {
        return dir.resolve("import-" + importId);
    }

    /** Deplace un fichier temporaire vers l'emplacement definitif de l'import. */
    public void store(long importId, Path source) throws IOException {
        Files.move(source, pathFor(importId), StandardCopyOption.REPLACE_EXISTING);
    }

    public InputStream open(long importId) throws IOException {
        return Files.newInputStream(pathFor(importId));
    }
}
