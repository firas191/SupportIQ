package com.supportiq.backend.digest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Synthese hebdomadaire — consultation et declenchement manuel (S6-J4).
 *
 * <p>Reserve aux <b>MANAGER+</b> : le digest agrege l'activite de toute l'equipe, meme perimetre
 * que le tableau de bord et le chat Insight.
 */
@RestController
@RequestMapping("/api/digests")
@PreAuthorize("hasRole('MANAGER')")
public class DigestController {

    private final DigestService service;
    private final DigestClient client;
    private final DigestMailer mailer;

    public DigestController(DigestService service, DigestClient client, DigestMailer mailer) {
        this.service = service;
        this.client = client;
        this.mailer = mailer;
    }

    @GetMapping
    public List<Digest> list() {
        return service.recent();
    }

    /**
     * Bouton « generer maintenant ».
     *
     * @param week semaine visee (lundi). Absente = la semaine ecoulee.
     * @param force regenere un digest existant. Sans ce drapeau, redemander la meme semaine renvoie
     *     simplement ce qui existe deja — un clic reflexe ne doit pas ecraser un digest envoye.
     */
    @PostMapping
    public Digest generate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
            @RequestParam(defaultValue = "false") boolean force) {
        return service.generate(week, force);
    }

    /** Reessaye l'envoi d'un digest existant, apres correction de la configuration SMTP. */
    @PostMapping("/{id}/send")
    public Digest send(@PathVariable long id) {
        return service.send(id);
    }

    /**
     * Telechargement du PDF.
     *
     * <p>Le PDF n'est pas stocke (V12) : il est **regenere** a la demande depuis les donnees de la
     * semaine. Contrepartie assumee — un telechargement coute un appel au service IA — pour ne pas
     * conserver un binaire derive qu'il faudrait migrer a chaque changement de mise en forme.
     */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable long id) {
        Digest digest = service.byId(id);
        byte[] pdf = client.generate(digest.weekStart()).pdf();
        if (pdf == null) {
            throw new DigestException(503, "Le rendu PDF n'est pas disponible sur ce serveur");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("digest-" + digest.weekStart() + ".pdf").build());
        return new ResponseEntity<>(pdf, headers, 200);
    }

    /**
     * Etat de la configuration d'envoi.
     *
     * <p>L'interface doit pouvoir dire « aucun envoi configure, le digest reste consultable ici »
     * plutot que de laisser croire qu'un courriel est parti. Un utilisateur qui suppose que le
     * message est arrive alors qu'aucun serveur SMTP n'existe est un utilisateur qu'on a trompe.
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "mailConfigured", mailer.configured(),
                "recipients", mailer.recipients());
    }
}
