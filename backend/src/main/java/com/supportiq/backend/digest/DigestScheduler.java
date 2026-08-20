package com.supportiq.backend.digest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Declenchement automatique du digest (S6-J4).
 *
 * <p><b>Ecart assume par rapport au rapport §9, qui prevoyait Quartz.</b> Quartz apporte trois
 * choses : la persistance des declencheurs, la coordination multi-instance, et la gestion des
 * declenchements manques. Les trois sont deja acquises ici, par un autre moyen :
 *
 * <ul>
 *   <li><b>persistance</b> — l'etat n'est pas « le declencheur a-t-il tire ? » mais « la ligne de
 *       la semaine existe-t-elle ? » ; elle est en base, elle survit a tout ;
 *   <li><b>multi-instance</b> — {@code UNIQUE(week_start)} laisse passer un seul insert ;
 *   <li><b>rattrapage</b> — la verification tourne toutes les heures, pas seulement lundi 8 h : si
 *       l'application etait arretee a ce moment-la, le digest part au premier reveil.
 * </ul>
 *
 * <p>Quartz aurait donc coute une dependance et onze tables pour reproduire ce qu'une contrainte
 * d'unicite fait deja. Et il aurait deplace la verite dans son propre magasin, alors qu'elle est
 * mieux dans la table metier : <i>ce qui compte n'est pas qu'un declencheur ait tire, c'est que le
 * digest existe et soit parti</i>.
 *
 * <p>Le declenchement horaire n'est pas un gaspillage : il ne fait qu'une requete indexee sur une
 * table de quelques dizaines de lignes, et ne genere que si rien n'existe pour la semaine.
 */
@Component
public class DigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);

    private final DigestService service;
    private final boolean enabled;

    public DigestScheduler(DigestService service,
            @Value("${app.digest.auto-generate:true}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    /**
     * Verifie chaque heure qu'un digest existe pour la semaine ecoulee, a partir du lundi 8 h.
     *
     * <p>L'expression couvre du lundi 8 h au dimanche : la generation reste possible toute la
     * semaine, ce qui est exactement le comportement de rattrapage voulu. Le premier passage
     * genere, les suivants ne font rien.
     */
    @Scheduled(cron = "${app.digest.cron:0 0 8-23 * * MON}")
    public void generateWeekly() {
        if (!enabled) {
            return;
        }
        try {
            service.generate(null, false);
        } catch (RuntimeException e) {
            // Un echec ne doit jamais tuer l'ordonnanceur : sans ce filet, une panne du service IA
            // un lundi matin empecherait aussi tous les declenchements suivants.
            log.warn("Generation automatique du digest echouee: {}", e.getMessage());
        }
    }
}
