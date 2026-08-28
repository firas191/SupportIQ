package com.supportiq.backend.intake;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Nettoyage d'un courriel entrant : reponses citees et signatures (S7-J4).
 *
 * <p><b>Ecart assume par rapport au rapport §9</b>, qui branchait le connecteur IMAP « sur ce meme
 * pipeline » que l'ingestion documentaire. Le pipeline documentaire fait deux choses : extraire du
 * texte d'un format binaire, et faire decouper ce texte en demandes par un modele. Un courriel n'a
 * besoin ni de l'une — il est deja en texte — ni de l'autre : c'est **une** demande, pas un lot. Le
 * faire traverser le reseau jusqu'au service IA pour retirer une signature serait payer un appel
 * distant pour une operation deterministe de manipulation de chaines.
 *
 * <p>Ce qui est reellement partage, c'est la sortie : dans les deux cas on obtient un ticket qui
 * part sur la chaine asynchrone existante.
 *
 * <p><b>Fonction pure, donc testable.</b> C'est ce qui compte ici : le nettoyage d'un courriel est
 * exactement le genre de code plein de cas particuliers ou une regle trop gourmande efface le
 * message lui-meme. Le garde-fou principal est donc en bas de ce fichier : <b>on ne coupe jamais si
 * la coupe ne laisse presque rien.</b>
 */
public final class EmailCleaner {

    /**
     * Marqueurs de debut de citation, FR et EN, tels que les produisent les clients courants.
     *
     * <p>La liste est volontairement **courte**. Chaque motif supplementaire augmente le risque
     * d'effacer un message legitime — « Le 12 mars, j'ai commande… » ressemble beaucoup a
     * « Le 12 mars, X a ecrit : ». Les motifs retenus se terminent tous par un deux-points ou une
     * ligne de separation, ce qui est justement ce qui distingue une en-tete de citation d'une
     * phrase ordinaire.
     */
    private static final List<Pattern> QUOTE_STARTS = List.of(
            // « Le 12/03/2026 a 14:32, Jean Dupont <jean@x.fr> a ecrit : »
            Pattern.compile("^\\s*Le\\s.{0,80}\\sa\\s+(e|é)crit\\s*:\\s*$", Pattern.CASE_INSENSITIVE),
            // « On Thu, Mar 12, 2026 at 2:32 PM, John Doe <john@x.com> wrote: »
            Pattern.compile("^\\s*On\\s.{0,80}\\swrote:\\s*$", Pattern.CASE_INSENSITIVE),
            // Separateurs poses par Outlook et consorts.
            Pattern.compile("^\\s*-{2,}\\s*(Message d'origine|Original Message|Forwarded message)\\s*-{2,}\\s*$",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*_{5,}\\s*$"),
            Pattern.compile("^\\s*(De|From)\\s*:\\s*.+$", Pattern.CASE_INSENSITIVE));

    /** Separateur de signature normalise par la RFC 3676 : deux tirets, une espace, fin de ligne. */
    private static final Pattern SIGNATURE_SEPARATOR = Pattern.compile("^--\\s?$");

    /**
     * Formules de signature frequentes. Ne coupent que si elles apparaissent **dans le dernier
     * tiers** du message : « Cordialement » au milieu d'un texte est une transition, pas une fin.
     */
    private static final List<Pattern> SIGNATURE_HINTS = List.of(
            Pattern.compile("^\\s*(Cordialement|Bien (a|à) vous|Sinc(e|è)rement|Merci d'avance)\\s*[,.]?\\s*$",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*(Best regards|Kind regards|Regards|Sincerely|Thanks)\\s*[,.]?\\s*$",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*(Envoy(e|é) de mon|Sent from my)\\s.+$", Pattern.CASE_INSENSITIVE));

    /**
     * Un nettoyage qui laisse moins que cela n'est pas un nettoyage, c'est une perte.
     *
     * <p>C'est le garde-fou central de cette classe. Sur un message court — « Bonjour, ma commande
     * n'est pas arrivee. Cordialement, Jean » — les regles de signature emporteraient la moitie du
     * contenu. Dans le doute, on garde le texte original : un ticket avec une signature de trop est
     * lisible, un ticket vide ne l'est pas.
     */
    private static final int MIN_KEPT_CHARS = 40;
    private static final double MIN_KEPT_RATIO = 0.25;

    private EmailCleaner() {
    }

    /** Corps utile du message : citations et signature retirees, texte original si le doute persiste. */
    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalised = raw.replace("\r\n", "\n").replace("\r", "\n");
        List<String> lines = List.of(normalised.split("\n", -1));

        List<String> withoutQuotes = stripQuotedReply(lines);
        List<String> withoutSignature = stripSignature(withoutQuotes);

        String candidate = String.join("\n", withoutSignature).strip();
        return isTooShort(candidate, normalised) ? normalised.strip() : candidate;
    }

    /** Sujet nettoye des prefixes de reponse et de transfert empiles. */
    public static String cleanSubject(String subject) {
        if (subject == null) {
            return "";
        }
        String cleaned = subject.strip();
        // Boucle plutot qu'un seul remplacement : « Re: Fwd: Re: … » est la norme, pas l'exception.
        while (true) {
            String next = cleaned.replaceFirst("(?i)^\\s*(re|r(e|é)p|fw|fwd|tr)\\s*(\\[\\d+\\])?\\s*:\\s*", "");
            if (next.equals(cleaned)) {
                return cleaned;
            }
            cleaned = next;
        }
    }

    private static List<String> stripQuotedReply(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (matchesAny(lines.get(i), QUOTE_STARTS)) {
                return lines.subList(0, i);
            }
        }
        // Les lignes prefixees par « > » sont retirees une a une : un client qui n'a pose aucune
        // en-tete de citation les produit quand meme.
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (!line.stripLeading().startsWith(">")) {
                kept.add(line);
            }
        }
        return kept;
    }

    private static List<String> stripSignature(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (SIGNATURE_SEPARATOR.matcher(lines.get(i)).matches()) {
                // Separateur normalise : aucune ambiguite, on coupe sans condition de position.
                return lines.subList(0, i);
            }
        }
        int threshold = (int) (lines.size() * 0.66);
        for (int i = lines.size() - 1; i >= threshold; i--) {
            if (matchesAny(lines.get(i), SIGNATURE_HINTS)) {
                return lines.subList(0, i);
            }
        }
        return lines;
    }

    private static boolean matchesAny(String line, List<Pattern> patterns) {
        return patterns.stream().anyMatch(p -> p.matcher(line).matches());
    }

    private static boolean isTooShort(String candidate, String original) {
        if (original.strip().length() <= MIN_KEPT_CHARS) {
            return candidate.isBlank();
        }
        return candidate.length() < MIN_KEPT_CHARS
                || candidate.length() < original.strip().length() * MIN_KEPT_RATIO;
    }
}
