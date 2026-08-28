package com.supportiq.backend.intake;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Nettoyage des courriels entrants (S7-J4).
 *
 * <p>Test unitaire pur : c'est precisement le genre de code plein de cas particuliers ou une regle
 * trop gourmande efface le message qu'elle devait nettoyer. Les cas qui comptent ne sont donc pas
 * « est-ce que ca nettoie » mais <b>« est-ce que ca detruit »</b>.
 */
class EmailCleanerTest {

    @Test
    void quotedReply_isRemoved() {
        String raw = """
                Bonjour, je n'ai toujours pas recu mon remboursement malgre votre message
                de la semaine derniere. Pouvez-vous verifier le dossier 48219 ?

                Le 12/03/2026 a 14:32, Support <support@exemple.fr> a ecrit :
                > Bonjour, votre demande est en cours de traitement.
                > Cordialement, le service client
                """;

        String cleaned = EmailCleaner.clean(raw);

        assertThat(cleaned).contains("48219");
        assertThat(cleaned).doesNotContain("en cours de traitement");
    }

    @Test
    void englishQuoteHeader_isRemoved() {
        String raw = """
                Hello, my parcel has still not arrived and the tracking page shows nothing
                since last Tuesday. Order reference is 77120.

                On Thu, Mar 12, 2026 at 2:32 PM, Support <support@example.com> wrote:
                > We are looking into it.
                """;

        assertThat(EmailCleaner.clean(raw)).doesNotContain("looking into it");
    }

    @Test
    void rfcSignatureSeparator_isRemoved() {
        String raw = """
                Bonjour, ma carte a ete debitee deux fois sur la commande 90211.
                Merci de regulariser cette double facturation rapidement.

                --
                Jean Dupont
                Directeur des achats
                06 12 34 56 78
                """;

        String cleaned = EmailCleaner.clean(raw);

        assertThat(cleaned).contains("90211");
        assertThat(cleaned).doesNotContain("Directeur des achats");
    }

    @Test
    void aShortMessageIsNeverGutted() {
        // Le garde-fou central. Les regles de signature emporteraient ici la moitie du contenu ;
        // dans le doute on garde tout. Un ticket avec une signature de trop reste lisible, un
        // ticket vide ne l'est pas.
        String raw = "Bonjour, ma commande n'est pas arrivee.\nCordialement,\nJean";

        assertThat(EmailCleaner.clean(raw)).contains("commande n'est pas arrivee");
    }

    @Test
    void aGreetingInTheMiddleIsNotASignature() {
        // « Merci d'avance » au milieu d'un texte est une transition, pas une fin. C'est pourquoi
        // les formules ne coupent que dans le dernier tiers du message.
        String raw = """
                Bonjour,

                Merci d'avance pour votre aide sur ce point precis, car il bloque tout le reste
                de notre migration et nous devons livrer avant la fin du mois.

                Le probleme est le suivant : depuis la mise a jour de mardi, aucun de nos agents
                ne parvient a se connecter a la console d'administration, ce qui nous empeche de
                traiter les demandes en attente.
                """;

        assertThat(EmailCleaner.clean(raw)).contains("console d'administration");
    }

    @Test
    void quotedLinesWithoutAHeaderAreStripped() {
        String raw = """
                Je confirme que le probleme est toujours present ce matin sur les deux comptes
                concernes, et qu'aucune de vos manipulations n'a change quoi que ce soit.

                > Avez-vous essaye de vous reconnecter ?
                > Merci de nous tenir informes.
                """;

        String cleaned = EmailCleaner.clean(raw);

        assertThat(cleaned).contains("toujours present");
        assertThat(cleaned).doesNotContain("vous reconnecter");
    }

    @Test
    void stackedReplyPrefixes_areAllRemovedFromTheSubject() {
        // « Re: Fwd: Re: » est la norme, pas l'exception : d'ou une boucle et non un remplacement.
        assertThat(EmailCleaner.cleanSubject("Re: Fwd: RE: Probleme de paiement"))
                .isEqualTo("Probleme de paiement");
        assertThat(EmailCleaner.cleanSubject("TR : Colis non recu")).isEqualTo("Colis non recu");
    }

    @Test
    void aSubjectThatMerelyStartsWithRe_isNotMutilated() {
        // « Remboursement » commence par « Re » : sans les deux-points obligatoires dans le motif,
        // le nettoyage amputerait le sujet de sa premiere syllabe.
        assertThat(EmailCleaner.cleanSubject("Remboursement non recu")).isEqualTo("Remboursement non recu");
    }

    @Test
    void nullAndBlankAreHandled() {
        assertThat(EmailCleaner.clean(null)).isEmpty();
        assertThat(EmailCleaner.clean("   ")).isEmpty();
        assertThat(EmailCleaner.cleanSubject(null)).isEmpty();
    }
}
