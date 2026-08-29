package com.supportiq.backend.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Declenche le rattrapage d'analyse periodiquement (S8-J1).
 *
 * <p><b>Actif par defaut.</b> C'est un correctif de justesse, et un correctif de justesse desactive
 * par defaut n'en est pas un : le trou qu'il ferme est precisement celui que personne ne surveille.
 * Le debit est en revanche volontairement bas — 50 tickets par quart d'heure — parce que chaque
 * republication finit en analyse, et qu'environ 46 % des analyses escaladent vers le LLM (mesure
 * S3-J5). Un rattrapage qui epuiserait le quota de la plateforme pour se rattraper lui-meme serait
 * une jolie facon de transformer un trou en panne.
 *
 * <p>Pour resorber un arriere important, on passe par l'endpoint ADMIN avec une limite explicite :
 * c'est une decision, elle se prend, elle ne se subit pas au reveil d'un ordonnanceur.
 *
 * <p><b>Avec rattrapage</b>, comme le digest (S6-J4) et contrairement aux sujets emergents
 * (S7-J1) : si l'application etait arretee, les tickets manquants sont toujours manquants au
 * redemarrage. Rien ne se perime ici — c'est meme la definition du probleme traite.
 */
@Component
@ConditionalOnProperty(name = "app.analysis-recovery.enabled", havingValue = "true",
        matchIfMissing = true)
public class AnalysisRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRecoveryScheduler.class);

    private final AnalysisRecoveryService service;

    public AnalysisRecoveryScheduler(AnalysisRecoveryService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${app.analysis-recovery.initial-delay-ms:120000}",
            fixedDelayString = "${app.analysis-recovery.interval-ms:900000}")
    public void sweep() {
        try {
            service.runOnce();
        } catch (Exception e) {
            // `debug` et non `warn` : 96 passages par jour, et un courtier arrete produirait 96
            // avertissements identiques. Meme arbitrage qu'au S7-J2 sur le detecteur d'anomalies —
            // des erreurs qui n'indiquent aucun defaut apprennent a ignorer les journaux d'erreurs.
            // Le vrai signal n'est pas ici : il est dans le compteur `unanalysed` de l'etat expose.
            log.debug("Passage de rattrapage impossible : {}", e.getMessage());
        }
    }
}
