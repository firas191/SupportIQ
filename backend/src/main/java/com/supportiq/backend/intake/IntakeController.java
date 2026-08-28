package com.supportiq.backend.intake;

import com.supportiq.backend.common.error.AiServiceException;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Ingestion de documents non structures (S7-J4, rapport §5.4).
 *
 * <p>Ouvert aux <b>AGENT+</b>, contrairement a l'import de fichier structure qui est reserve aux
 * ADMIN. La difference est reelle : un import CSV cree dix mille tickets d'un coup et definit un
 * mapping de colonnes qui engage tout le lot, alors qu'ici un agent depose le PDF qu'un client
 * vient d'envoyer, relit une douzaine de demandes et les valide une par une. C'est du travail de
 * traitement, pas d'administration.
 */
@RestController
@RequestMapping("/api/intake")
@PreAuthorize("hasRole('AGENT')")
public class IntakeController {

    private final IntakeService service;

    public IntakeController(IntakeService service) {
        this.service = service;
    }

    /** Etape 1 : extraction. Ne cree rien. */
    @PostMapping("/documents")
    public IntakeModels.ExtractionResult extract(@RequestParam("file") MultipartFile file) {
        try {
            return service.extract(file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new AiServiceException(400, "Ingestion documentaire", "intake",
                    "Le fichier n'a pas pu etre lu.");
        }
    }

    /**
     * Etape 2 : creation des demandes retenues par l'agent.
     *
     * <p>Le lot revient du navigateur, corrige. Il n'est volontairement pas relu depuis un stockage
     * serveur — voir {@link IntakeModels.ConfirmRequest} pour l'ecart assume avec l'import de
     * fichier structure.
     */
    @PostMapping("/confirm")
    public IntakeModels.ConfirmResult confirm(@Valid @RequestBody IntakeModels.ConfirmRequest body) {
        return service.confirm(body);
    }
}
