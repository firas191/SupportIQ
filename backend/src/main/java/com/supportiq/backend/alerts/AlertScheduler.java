package com.supportiq.backend.alerts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Detection periodique des anomalies de volume (S7-J2).
 *
 * <p><b>Cinq minutes, et non une heure.</b> La mesure porte sur des heures pleines, mais la
 * frequence de mesure et l'unite de mesure sont deux choses differentes : passer toutes les cinq
 * minutes fait qu'un pic survenu a 14 h 05 est signale peu apres 15 h plutot qu'a 16 h. Le cout est
 * negligeable — quelques centaines de points a decomposer, aucun appel de modele — et la contrainte
 * d'unicite (V16) garantit qu'un meme pic n'est signale qu'une fois, quel que soit le nombre de
 * passages qui le retrouvent.
 *
 * <p><b>Rattrapage inclus</b>, contrairement aux sujets emergents (S7-J1) : {@code lookback} couvre
 * les dernieres heures, pas seulement la derniere. Une alerte manquee ne se rattrape pas toute
 * seule, elle — l'anomalie a eu lieu, et le fait qu'un redemarrage l'ait masquee ne la rend pas
 * moins reelle. C'est la meme raison qui fait rattraper le digest et pas les sujets.
 */
@Component
public class AlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertScheduler.class);

    private final AlertService service;
    private final boolean enabled;
    private final int lookback;

    public AlertScheduler(AlertService service,
            @Value("${app.alerts.auto-detect:true}") boolean enabled,
            @Value("${app.alerts.lookback-hours:3}") int lookback) {
        this.service = service;
        this.enabled = enabled;
        this.lookback = lookback;
    }

    @Scheduled(cron = "${app.alerts.cron:0 */5 * * * *}")
    public void detect() {
        if (!enabled) {
            return;
        }
        try {
            service.detect(lookback);
        } catch (RuntimeException e) {
            // Un echec ne doit jamais tuer l'ordonnanceur. Et il est journalise en `debug` et non en
            // `warn` : ce job tourne 288 fois par jour, et un service IA arrete produirait 288
            // avertissements identiques — le bruit qui fait qu'on cesse de lire les journaux.
            log.debug("Detection d'anomalies echouee: {}", e.getMessage());
        }
    }
}
