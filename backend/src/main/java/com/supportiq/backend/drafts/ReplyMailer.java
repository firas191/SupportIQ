package com.supportiq.backend.drafts;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Envoi au client de la reponse validee par un agent.
 *
 * <p><b>C'est la seule action de la plateforme qui atteigne une personne exterieure.</b> Le digest
 * s'adresse a l'equipe ; ceci part chez un client, sous le nom de l'entreprise, et ne se rattrape
 * pas. Trois garde-fous en decoulent, tous par defaut du cote prudent.
 *
 * <ol>
 *   <li><b>Desactive par defaut</b> ({@code app.reply.enabled=false}). Un environnement de
 *       developpement ne doit pas pouvoir ecrire a de vraies personnes parce que quelqu'un a
 *       clique sur « Valider ». L'activation est un geste conscient, documente dans le {@code .env}.
 *   <li><b>Aucun envoi sans destinataire connu.</b> Un ticket importe par fichier n'a pas toujours
 *       d'adresse ; on ne devine pas.
 *   <li><b>Un echec n'annule pas la decision humaine</b> (voir {@link DraftService}). La validation
 *       est un fait, la livraison un autre.
 * </ol>
 *
 * <p>Ce composant duplique la forme de {@code DigestMailer} sans la partager : les deux different
 * sur l'expediteur, le destinataire, la piece jointe et surtout la <b>gravite</b>. Les fusionner
 * derriere une abstraction commune ferait disparaitre precisement ce qui les distingue.
 */
@Component
public class ReplyMailer {

    private static final Logger log = LoggerFactory.getLogger(ReplyMailer.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final boolean enabled;
    private final String from;
    private final String host;

    public ReplyMailer(ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.reply.enabled:false}") boolean enabled,
            @Value("${app.reply.from:support@localhost}") String from,
            @Value("${spring.mail.host:}") String host) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
        this.host = host;
    }

    /**
     * L'envoi de reponses est possible.
     *
     * <p>L'hote est verifie explicitement : une propriete {@code spring.mail.host} renseignee a la
     * chaine vide compte comme presente pour Spring Boot, qui cree alors un bean pointant vers
     * nulle part. Sans ce controle, l'interface annoncerait « envoi actif » et l'echec n'arriverait
     * qu'au premier clic — sur un message destine a un client.
     */
    public boolean enabled() {
        return enabled && !host.isBlank() && mailSender.getIfAvailable() != null;
    }

    /**
     * Envoie la reponse. Leve {@link DraftException} en cas d'echec, pour que l'appelant le trace.
     *
     * @param to adresse du client, deja verifiee non vide par l'appelant
     * @param subject sujet du ticket, repris en « Re: … » pour que le client raccroche le fil
     * @param body texte final — la version corrigee par l'agent si elle existe
     */
    public void send(String to, String subject, String body) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (!enabled() || sender == null) {
            throw new DraftException(503, "L'envoi de reponses au client n'est pas active");
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(replySubject(subject));
            // Texte brut, pas de HTML : le brouillon a ete redige et relu comme du texte. Le
            // convertir en HTML ferait diverger ce que l'agent a valide de ce que le client recoit.
            helper.setText(body, false);

            sender.send(message);
            log.info("Reponse envoyee a {}", to);

        } catch (Exception e) {
            log.warn("Envoi de la reponse a {} echoue: {}", to, e.getMessage());
            throw new DraftException(502, "Envoi impossible : " + e.getMessage());
        }
    }

    private static String replySubject(String subject) {
        String base = subject == null || subject.isBlank() ? "votre demande" : subject.trim();
        // Ne pas empiler « Re: Re: » si le sujet du ticket en portait deja un.
        return base.regionMatches(true, 0, "re:", 0, 3) ? base : "Re: " + base;
    }
}
