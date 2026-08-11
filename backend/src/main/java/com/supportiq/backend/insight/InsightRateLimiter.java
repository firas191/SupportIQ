package com.supportiq.backend.insight;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Quota de questions par utilisateur (S6-J3).
 *
 * <p>Dette identifiee dans l'ADR-0007 et reglee ici, comme annonce. Chaque question coute jusqu'a
 * quatre appels de modele et une requete sur la base : cent questions d'affilee epuisent le budget
 * de jetons de la journee et occupent le pool de connexions en lecture seule.
 *
 * <p><b>Ce n'est pas une protection contre l'attaque</b> — l'utilisateur est authentifie et
 * MANAGER+. C'est une protection contre l'emballement : une boucle dans un script, un onglet laisse
 * ouvert avec un rafraichissement automatique, ou simplement quelqu'un qui explore avec
 * enthousiasme. Le mode de defaillance vise est l'accident, pas la malveillance.
 *
 * <p>Quota par <b>utilisateur</b> et non par adresse IP : derriere un NAT d'entreprise, tout le
 * monde partage la meme IP, et une limite par IP punirait l'equipe pour l'usage d'une personne.
 *
 * <p>Etat <b>en memoire</b>, comme le limiteur du webhook (S2-J4) : suffisant en mono-instance. En
 * multi-instance il faudrait Redis, sans quoi chaque noeud accorde son propre quota.
 */
@Component
public class InsightRateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int perHour;

    public InsightRateLimiter(@Value("${app.insight.questions-per-hour:30}") int perHour) {
        this.perHour = perHour;
    }

    /** Consomme un jeton. {@code false} quand le quota horaire est epuise. */
    public boolean tryConsume(String userEmail) {
        return buckets
                .computeIfAbsent(userEmail, key -> Bucket.builder()
                        // Remplissage progressif (`greedy` sur une heure) plutot qu'un rechargement
                        // en bloc : avec un bloc, un utilisateur bloque a 14 h 59 attend jusqu'a
                        // 15 h 00 puis peut tout reconsommer d'un coup. Le remplissage continu
                        // rend un jeton toutes les deux minutes, ce qui degrade au lieu de couper.
                        .addLimit(Bandwidth.builder()
                                .capacity(perHour)
                                .refillGreedy(perHour, Duration.ofHours(1))
                                .build())
                        .build())
                .tryConsume(1);
    }
}
