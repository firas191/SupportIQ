package com.supportiq.backend.sla;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recalcul periodique du risque SLA (S7-J3).
 *
 * <p><b>Toutes les dix minutes, et c'est un compromis explicite.</b> Le score vieillit par
 * construction : sa variable dominante est le temps restant avant echeance, qui diminue en continu.
 * Recalculer en permanence serait exact et couteux ; recalculer une fois par heure laisserait la
 * file mal triee la moitie du temps sur les tickets HIGH, dont le budget entier ne fait que 4 h.
 *
 * <p>Dix minutes, c'est 2,5 % du budget le plus court. L'interface affiche l'age du score plutot que
 * de laisser croire a une valeur instantanee.
 */
@Component
public class SlaScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlaScheduler.class);

    private final SlaScoringClient client;
    private final boolean enabled;

    public SlaScheduler(SlaScoringClient client,
            @Value("${app.sla.auto-score:true}") boolean enabled) {
        this.client = client;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.sla.cron:0 */10 * * * *}")
    public void score() {
        if (!enabled) {
            return;
        }
        try {
            SlaScoringClient.Result result = client.score();
            log.debug("Risque SLA recalcule: {} tickets, {} a risque (modele={})",
                    result.scored(), result.atRisk(), result.model());
        } catch (RuntimeException e) {
            // `debug` et non `warn` : 144 passages par jour, et un service IA arrete produirait
            // 144 avertissements identiques — le bruit qui fait cesser de lire les journaux.
            // Meme raisonnement qu'au detecteur d'anomalies (S7-J2).
            log.debug("Recalcul du risque SLA echoue: {}", e.getMessage());
        }
    }
}
