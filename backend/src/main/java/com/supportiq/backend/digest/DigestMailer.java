package com.supportiq.backend.digest;

import com.supportiq.backend.common.error.AiServiceException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Envoi du digest par courriel (S6-J4).
 *
 * <p><b>C'est le premier endroit ou quelque chose sort de la plateforme.</b> Jusqu'ici une erreur
 * restait dans la base ou a l'ecran ; a partir d'ici elle arrive dans la boite d'un responsable, et
 * on ne peut pas la reprendre. Deux consequences sur la conception :
 *
 * <ul>
 *   <li>on n'envoie <b>jamais</b> un digest a moitie genere — l'appelant ne declenche l'envoi
 *       qu'apres persistance reussie ;
 *   <li>un echec d'envoi est <b>trace et visible</b> (colonne {@code send_error}), jamais avale.
 *       Un courriel qui ne part pas en silence est pire qu'une erreur affichee : personne ne
 *       s'apercoit que la synthese de la semaine n'est jamais arrivee.
 * </ul>
 *
 * <p>{@link ObjectProvider} sur {@link JavaMailSender} : sans configuration SMTP, Spring Boot ne
 * cree pas ce bean. Une injection directe empecherait alors <b>tout le backend</b> de demarrer, ce
 * qui est disproportionne — un environnement de developpement sans serveur de courriel doit rester
 * utilisable, le digest se consulte alors a l'ecran.
 */
@Component
public class DigestMailer {

    private static final Logger log = LoggerFactory.getLogger(DigestMailer.class);
    private static final DateTimeFormatter FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;
    private final String recipients;
    private final String host;

    public DigestMailer(ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.digest.from:supportiq@localhost}") String from,
            @Value("${app.digest.recipients:}") String recipients,
            @Value("${spring.mail.host:}") String host) {
        this.mailSender = mailSender;
        this.from = from;
        this.recipients = recipients;
        this.host = host;
    }

    /**
     * Envoi possible : un serveur, un expediteur, des destinataires.
     *
     * <p>L'hote est verifie <b>explicitement</b> et pas seulement via la presence du bean.
     * Subtilite de Spring Boot : `spring.mail.host` renseigne a la chaine vide compte comme
     * « present », donc le bean est cree et pointe vers nulle part. Sans ce controle, l'interface
     * annoncerait « envoi configure » et l'echec n'arriverait qu'a la premiere tentative — au
     * moment ou l'on s'y attend le moins.
     */
    public boolean configured() {
        return !host.isBlank() && mailSender.getIfAvailable() != null && !recipients.isBlank();
    }

    public String recipients() {
        return recipients;
    }

    /**
     * Envoie le digest. Leve {@link AiServiceException} en cas d'echec, pour que l'appelant le trace.
     *
     * @param pdf piece jointe, ou {@code null} — le rendu PDF depend de bibliotheques systeme et
     *     peut manquer. Un digest sans piece jointe reste lisible dans le corps du message ; ne
     *     rien envoyer parce que la mise en page a echoue serait perdre l'essentiel pour la forme.
     */
    public void send(Digest digest, byte[] pdf) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (!configured() || sender == null) {
            throw AiServiceException.digest(503, "Envoi de courriel non configure");
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, pdf != null, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipients.split("\\s*,\\s*"));
            helper.setSubject(subject(digest.weekStart()));
            // Le Markdown est envoye tel quel dans le corps : il reste lisible sans rendu, et
            // c'est le filet quand le PDF manque. Un corps HTML genere serait une troisieme mise
            // en forme a maintenir, pour la meme information.
            helper.setText(digest.markdown(), false);

            if (pdf != null) {
                helper.addAttachment("digest-" + digest.weekStart() + ".pdf",
                        new ByteArrayResource(pdf), "application/pdf");
            }

            sender.send(message);
            log.info("Digest {} envoye a {}", digest.weekStart(), recipients);

        } catch (Exception e) {
            log.warn("Envoi du digest {} echoue: {}", digest.weekStart(), e.getMessage());
            throw AiServiceException.digest(502, "Envoi impossible : " + e.getMessage());
        }
    }

    private static String subject(LocalDate weekStart) {
        return "SupportIQ — synthese de la semaine du " + weekStart.format(FR);
    }
}
