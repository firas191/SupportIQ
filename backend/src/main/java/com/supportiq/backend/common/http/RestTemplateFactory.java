package com.supportiq.backend.common.http;

import org.springframework.web.client.RestTemplate;

/**
 * Fabrique des clients HTTP vers le service IA.
 *
 * <p><b>Pourquoi cette interface existe, et pourquoi elle est fonctionnelle.</b>
 *
 * <p>Le projet compte neuf clients vers le plan de calcul, et chacun construisait son
 * {@link RestTemplate} a la main — neuf copies du meme bloc, ne differant que par deux delais.
 * Ce n'etait pas qu'une duplication esthetique : la lecon du S6-J3 (ne jamais passer par
 * {@code RestTemplateBuilder}, qui produisait un client envoyant un corps vide) etait recopiee
 * neuf fois en commentaire, ce qui garantit qu'un dixieme client l'oubliera.
 *
 * <p>Le second motif est le plus important : un {@code RestTemplate} construit dans un
 * constructeur est **intestable**. {@code MockRestServiceServer} doit se lier a l'instance, et une
 * instance privee creee sur place n'est atteignable par personne. C'est exactement pour cela que
 * deux clients casses ont pu vivre plusieurs semaines dans le depot sans qu'aucun test ne le voie :
 * les suites d'integration pointent toutes vers {@code localhost:1} pour verifier la
 * <b>degradation</b>, et personne ne verifiait qu'un appel <b>reussi</b> partait correctement.
 *
 * <p>Interface fonctionnelle a dessein : un test passe {@code (connect, read) -> template} et
 * recupere la main sur le transport, sans qu'aucun constructeur « pour les tests » ne vienne
 * s'ajouter a la production.
 */
@FunctionalInterface
public interface RestTemplateFactory {

    /**
     * @param connectTimeoutMs delai d'etablissement de la connexion. Court partout : si le service
     *     IA ne repond pas au TCP en trois secondes, il est arrete, pas lent.
     * @param readTimeoutMs delai de lecture. Tres variable selon l'appel — quelques secondes pour
     *     un calcul numerique, plusieurs minutes pour une chaine d'appels de modele. C'est le seul
     *     parametre que chaque client a vraiment besoin de choisir.
     */
    RestTemplate create(int connectTimeoutMs, int readTimeoutMs);
}
