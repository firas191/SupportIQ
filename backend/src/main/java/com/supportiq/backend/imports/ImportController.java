package com.supportiq.backend.imports;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    /** Upload d'un fichier structure (ADMIN). Retourne l'apercu + le rapport d'erreurs. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportPreviewResponse upload(@RequestParam("file") MultipartFile file, Authentication auth) {
        return importService.importFile(file, auth.getName());
    }

    /** Confirmation avec mapping de colonnes -> insertion des tickets (ADMIN). */
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN')")
    public ConfirmImportResponse confirm(@PathVariable long id,
            @Valid @RequestBody ConfirmImportRequest request) {
        return importService.confirm(id, request.mapping());
    }
}
