package com.supportiq.backend.intake;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Connecteur de boite aux lettres : un courriel non lu devient un ticket (S7-J4, rapport §9).
 *
 * <p><b>Desactive par defaut.</b> Comme l'envoi de reponses (demi-journee S6), c'est une
 * fonctionnalite qui touche un systeme exterieur : mal configuree, elle vide une vraie boite aux
 * lettres en la marquant comme lue. L'activation est un geste conscient.
 *
 * <p><b>Le marquage « lu » est la seule protection contre les doublons</b>, et il est fait
 * <b>apres</b> la creation du ticket, jamais avant. L'ordre inverse perdrait definitivement un
 * message si l'insertion echouait : le courriel serait lu et aucun ticket n'existerait. Dans
 * l'ordre choisi, un plantage entre les deux produit au pire un doublon — un defaut visible et
 * reparable, contre une perte silencieuse.
 *
 * <p>Une alternative aurait ete de stocker les {@code Message-ID} deja traites pour dedupliquer
 * proprement. Elle demande une table et un cycle de vie ; le drapeau IMAP est deja la, il est
 * atomique cote serveur, et c'est exactement ce a quoi il sert.
 */
@Component
public class EmailPoller {

    private static final Logger log = LoggerFactory.getLogger(EmailPoller.class);

    /** Borne par passage : une boite laissee sans surveillance ne doit pas noyer la file d'un coup. */
    private static final int MAX_PER_RUN = 50;
    private static final int MAX_BODY_CHARS = 20_000;

    private final IntakeService intake;

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String folderName;

    public EmailPoller(IntakeService intake,
            @Value("${app.intake.email.enabled:false}") boolean enabled,
            @Value("${app.intake.email.host:}") String host,
            @Value("${app.intake.email.port:993}") int port,
            @Value("${app.intake.email.username:}") String username,
            @Value("${app.intake.email.password:}") String password,
            @Value("${app.intake.email.folder:INBOX}") String folderName) {
        this.intake = intake;
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.folderName = folderName;
    }

    /** Actif seulement si l'activation **et** la configuration sont presentes. */
    public boolean configured() {
        return enabled && !host.isBlank() && !username.isBlank();
    }

    @Scheduled(cron = "${app.intake.email.cron:0 */2 * * * *}")
    public void poll() {
        if (!configured()) {
            return;
        }
        try {
            fetchAndCreate();
        } catch (Exception e) {
            // `warn` et non `error` : une boite injoignable est un incident d'environnement, pas
            // un defaut du service. Mais contrairement aux ordonnanceurs qui tournent 288 fois par
            // jour (S7-J2, S7-J3), celui-ci passe 720 fois — et surtout, son echec signifie que
            // des demandes clients n'entrent pas. Il merite d'etre vu.
            log.warn("Releve de la boite aux lettres impossible : {}", e.getMessage());
        }
    }

    /**
     * Pas de {@code @Transactional} ici, et c'est deliberé : chaque courriel est cree dans sa
     * propre transaction par {@link IntakeService#createFromEmail}. Une transaction englobant toute
     * la releve annulerait les tickets deja crees si le douzieme message echouait — alors que les
     * onze premiers ont deja ete marques comme lus cote serveur IMAP, et seraient donc perdus.
     */
    private void fetchAndCreate() throws Exception {
        Properties properties = new Properties();
        properties.put("mail.store.protocol", "imaps");
        properties.put("mail.imaps.host", host);
        properties.put("mail.imaps.port", String.valueOf(port));
        properties.put("mail.imaps.connectiontimeout", "5000");
        properties.put("mail.imaps.timeout", "15000");

        Store store = Session.getInstance(properties).getStore("imaps");
        store.connect(host, username, password);

        try (store) {
            Folder folder = store.getFolder(folderName);
            folder.open(Folder.READ_WRITE);
            try {
                Message[] unread = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                int created = 0;

                for (int i = 0; i < unread.length && i < MAX_PER_RUN; i++) {
                    Message message = unread[i];
                    if (!createTicket(message)) {
                        continue;
                    }
                    // Marque APRES creation : voir le commentaire de classe.
                    message.setFlag(Flags.Flag.SEEN, true);
                    created++;
                }

                if (created > 0) {
                    log.info("{} courriel(s) transforme(s) en ticket", created);
                }
            } finally {
                folder.close(false);
            }
        }
    }

    /** @return {@code true} si un ticket a ete cree (donc si le message peut etre marque lu). */
    private boolean createTicket(Message message) throws Exception {
        String subject = EmailCleaner.cleanSubject(message.getSubject());
        String body = EmailCleaner.clean(textOf(message));

        if (subject.isBlank() && body.isBlank()) {
            // Un message entierement vide (accuse de reception automatique, pieces jointes seules)
            // ne donne pas un ticket exploitable. On le laisse **non lu** : quelqu'un devra le
            // regarder, et le supprimer silencieusement serait la pire option.
            log.info("Courriel sans contenu exploitable, laisse non lu");
            return false;
        }

        intake.createFromEmail(
                // `external_ref` = Message-ID : c'est l'identifiant stable du courriel, defini par
                // la RFC 5322. Il donne au passage une deduplication naturelle si le drapeau IMAP
                // venait a etre reinitialise cote serveur.
                firstHeader(message, "Message-ID"),
                senderOf(message),
                subject.isBlank() ? "(sans objet)" : truncate(subject, 500),
                truncate(body, MAX_BODY_CHARS));
        return true;
    }

    /**
     * Corps texte du message.
     *
     * <p>On prefere systematiquement la partie {@code text/plain} quand elle existe : la partie
     * HTML des courriels contient des styles, des pixels de suivi et des tables de mise en page,
     * dont rien n'a sa place dans un ticket. Le repli HTML est degrossi par une suppression de
     * balises — grossiere, mais elle ne s'applique qu'aux expediteurs qui n'envoient que du HTML.
     */
    private String textOf(Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            return String.valueOf(part.getContent());
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            String htmlFallback = null;
            for (int i = 0; i < multipart.getCount(); i++) {
                Part child = multipart.getBodyPart(i);
                if (child.isMimeType("text/plain")) {
                    return String.valueOf(child.getContent());
                }
                if (child.isMimeType("text/html") && htmlFallback == null) {
                    htmlFallback = stripHtml(String.valueOf(child.getContent()));
                } else if (child.isMimeType("multipart/*")) {
                    String nested = textOf(child);
                    if (!nested.isBlank()) {
                        return nested;
                    }
                }
            }
            return htmlFallback == null ? "" : htmlFallback;
        }
        if (part.isMimeType("text/html")) {
            return stripHtml(String.valueOf(part.getContent()));
        }
        return "";
    }

    private static String stripHtml(String html) {
        return html.replaceAll("(?is)<(script|style).*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("[ \\t]{2,}", " ");
    }

    private static String senderOf(Message message) throws Exception {
        var from = message.getFrom();
        if (from == null || from.length == 0) {
            return null;
        }
        return from[0] instanceof InternetAddress address ? address.getAddress() : from[0].toString();
    }

    private static String firstHeader(Message message, String name) throws Exception {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? null : truncate(values[0], 255);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
