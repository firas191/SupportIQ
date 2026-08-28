package com.supportiq.backend.topics;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Sujets emergents : consultation du dernier instantane, declenchement d'un nouveau (S7-J1).
 *
 * <p>La detection tourne <b>hors transaction</b>, comme la generation d'un brouillon (S5-J4) et
 * celle d'un digest (S6-J4) : plusieurs minutes d'attente sur un appel distant, pour zero ecriture
 * cote Spring — c'est le service IA qui ecrit. Tenir une transaction ouverte pendant ce temps
 * epuiserait le pool de connexions bien avant que le premier instantane n'arrive.
 */
@Service
public class TopicService {

    private final TopicRepository repository;
    private final TopicsClient client;

    public TopicService(TopicRepository repository, TopicsClient client) {
        this.repository = repository;
        this.client = client;
    }

    /**
     * Dernier instantane connu.
     *
     * @param topics peut etre vide sans qu'il y ait de panne : soit rien n'a encore ete calcule,
     *     soit le corpus recent ne contient aucun groupe assez dense. L'interface doit distinguer
     *     les deux, d'ou {@code computedAt} — {@code null} signifie « jamais calcule ».
     */
    public record Snapshot(Instant computedAt, int windowDays, List<Topic> topics) {
    }

    public Snapshot latest() {
        List<Topic> topics = repository.latest();
        if (topics.isEmpty()) {
            return new Snapshot(null, 0, List.of());
        }
        Topic first = topics.get(0);
        return new Snapshot(first.computedAt(), first.windowDays(), topics);
    }

    /** Recalcule, puis renvoie l'instantane fraichement ecrit. */
    public Snapshot detect(Integer windowDays) {
        client.detect(windowDays);
        return latest();
    }
}
