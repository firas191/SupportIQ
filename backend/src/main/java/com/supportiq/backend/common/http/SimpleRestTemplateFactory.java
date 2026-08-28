package com.supportiq.backend.common.http;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Implementation de production : fabrique de requetes posee <b>explicitement</b>.
 *
 * <p>C'est le seul endroit du depot ou cette decision est prise, et elle merite d'etre expliquee
 * une fois pour toutes.
 *
 * <p><b>Pourquoi pas {@code RestTemplateBuilder}.</b> Constate au S6-J3 : les deux clients
 * construits par le builder ({@code InsightClient} et {@code DraftClient}) envoyaient un corps
 * <b>vide</b>. FastAPI repondait 422 « Field required » sans jamais voir la question, et les deux
 * clients qui fonctionnaient depuis des semaines utilisaient, eux, {@code new RestTemplate()}.
 *
 * <p>Le builder choisit sa fabrique de requetes selon les bibliotheques presentes au demarrage.
 * Plutot que de dependre de cette detection — donc du classpath, donc d'une dependance ajoutee un
 * jour par quelqu'un d'autre — on pose la fabrique nous-memes. Les delais d'expiration, seule
 * raison d'avoir utilise le builder, sont conserves.
 *
 * <p>Corollaire a ne pas oublier cote appelant : le {@code Content-Type} doit porter un charset
 * explicite. Un {@code HttpEntity<String>} sans charset est ecrit en ISO-8859-1 par
 * {@code StringHttpMessageConverter} ; tant que le texte est en ASCII pur les deux encodages
 * coincident, ce qui masque le probleme jusqu'au premier accent.
 */
@Component
public class SimpleRestTemplateFactory implements RestTemplateFactory {

    @Override
    public RestTemplate create(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }
}
