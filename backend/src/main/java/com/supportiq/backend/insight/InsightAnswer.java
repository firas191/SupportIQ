package com.supportiq.backend.insight;

import java.util.List;

/**
 * Reponse a une question de manager (S6-J3, miroir de `InsightResponse` cote service IA).
 *
 * <p>Le <b>SQL est renvoye a l'interface</b>, volontairement. C'est le « mode transparent » du
 * rapport §9 : un chiffre qu'on ne peut pas verifier est un chiffre qu'on croit ou qu'on ignore,
 * jamais un chiffre sur lequel on decide. Et la mesure du S6-J2 lui donne une seconde utilite —
 * l'agent repond parfois a une question <i>voisine</i> de celle posee (il a substitue le sujet du
 * ticket au corps du message). Aucune barriere technique ne detecte cela ; montrer la requete, si.
 */
public record InsightAnswer(
        String question,
        String sql,
        /* Synthese en langage naturel. Vide si le service de generation etait indisponible : les
         * lignes restent exploitables, l'interface affiche le tableau sans la phrase. */
        String answer,
        Chart chart,
        List<String> columns,
        List<List<Object>> rows,
        int rowCount,
        /* Le plafond de lignes a tronque. Sans ce drapeau, un manager lirait « 500 » la ou il y en
         * a 12 000 et deciderait sur un chiffre faux. */
        boolean truncated) {

    /**
     * Graphique a tracer, <b>deduit du resultat par le code</b> cote service IA (S6-J2).
     *
     * <p>{@code type = "none"} est une valeur normale, pas une absence : {@code reason} dit
     * pourquoi, ce qui permet d'ecrire « une seule valeur, pas de graphique » plutot que d'afficher
     * un cadre vide — lequel se lit comme une panne.
     */
    public record Chart(String type, String x, String y, String reason) {
    }
}
