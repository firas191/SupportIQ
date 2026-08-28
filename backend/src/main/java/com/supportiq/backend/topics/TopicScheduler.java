package com.supportiq.backend.topics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Detection nocturne des sujets emergents (S7-J1, « job periodique » du rapport §9).
 *
 * <p><b>Pourquoi la nuit, et pourquoi une seule fois par jour.</b> Le calcul est lourd (reduction
 * de dimension sur plusieurs milliers de vecteurs, puis un appel de modele par sujet) et son
 * resultat ne change pas d'une heure a l'autre : les sujets emergents se mesurent en jours. Le
 * faire tourner plus souvent couterait des jetons pour reecrire a peu pres le meme instantane.
 *
 * <p><b>Pas de rattrapage horaire ici</b>, contrairement au digest. La difference est reelle : un
 * digest manque est un document qui n'existera jamais pour cette semaine — donc il faut le
 * rattraper. Un instantane de sujets manque est simplement remplace par celui du lendemain, qui
 * couvre de toute facon la meme fenetre glissante. Rattraper produirait deux instantanes a
 * quelques heures d'ecart, sans rien apporter.
 *
 * <p>Consequence assumee : si l'application est arretee a 3 h du matin, l'ecran affiche
 * l'instantane de la veille. C'est pour cela qu'il porte visiblement sa date de calcul.
 */
@Component
public class TopicScheduler {

    private static final Logger log = LoggerFactory.getLogger(TopicScheduler.class);

    private final TopicService service;
    private final boolean enabled;

    public TopicScheduler(TopicService service,
            @Value("${app.topics.auto-detect:true}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.topics.cron:0 30 3 * * *}")
    public void detectNightly() {
        if (!enabled) {
            return;
        }
        try {
            TopicService.Snapshot snapshot = service.detect(null);
            log.info("Sujets emergents recalcules: {} sujets", snapshot.topics().size());
        } catch (RuntimeException e) {
            // Un echec ne doit jamais tuer l'ordonnanceur : sans ce filet, une panne du service IA
            // une nuit empecherait aussi tous les declenchements suivants. Meme filet qu'au digest.
            log.warn("Detection automatique des sujets echouee: {}", e.getMessage());
        }
    }
}
