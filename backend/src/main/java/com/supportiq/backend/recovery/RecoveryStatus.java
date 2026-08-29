package com.supportiq.backend.recovery;

/**
 * Etat du rattrapage d'analyse (S8-J1).
 *
 * <p>Les quatre chiffres repondent a des questions differentes, et c'est pour cela qu'ils sont
 * separes plutot que resumes en un seul :
 *
 * <ul>
 *   <li>{@code unanalysed} — combien de tickets echappent aujourd'hui au pipeline. Le chiffre qui
 *       n'existait pas, et dont l'absence a laisse 60 016 tickets sans analyse pendant des jours ;
 *   <li>{@code pending} — combien ont ete republies et attendent ;
 *   <li>{@code givenUp} — combien ont epuise leurs tentatives. <b>Celui-la demande une action
 *       humaine</b> : s'il ne bouge pas, tout va bien ; s'il monte, quelque chose fait echouer
 *       l'analyse de facon reproductible et le rattrapage ne peut rien y faire ;
 *   <li>{@code outOfScope} — exclus deliberement (corpus de charge). Present pour que l'exclusion
 *       soit <b>visible</b> : une regle qu'on ne voit nulle part finit par etre oubliee, puis
 *       redecouverte comme un bug.
 * </ul>
 */
public record RecoveryStatus(
        long unanalysed,
        long pending,
        long givenUp,
        long outOfScope) {
}
