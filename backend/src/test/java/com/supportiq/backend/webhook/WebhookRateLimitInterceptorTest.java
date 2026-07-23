package com.supportiq.backend.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unitaire : le seau a jetons Bucket4j laisse passer jusqu'a la capacite puis leve 429. */
class WebhookRateLimitInterceptorTest {

    @Test
    void allowsUpToCapacity_thenThrows() {
        WebhookProperties props = new WebhookProperties("k", "s",
                new WebhookProperties.RateLimit(2, 2, Duration.ofHours(1)));  // recharge lente : pas de refill pendant le test
        WebhookRateLimitInterceptor interceptor = new WebhookRateLimitInterceptor(props);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Api-Key", "k");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();   // 1er jeton
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();   // 2e jeton
        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(WebhookRateLimitException.class);                        // seau vide -> 429
    }

    @Test
    void separateApiKeys_haveSeparateBuckets() {
        WebhookProperties props = new WebhookProperties("k", "s",
                new WebhookProperties.RateLimit(1, 1, Duration.ofHours(1)));
        WebhookRateLimitInterceptor interceptor = new WebhookRateLimitInterceptor(props);
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockHttpServletRequest a = new MockHttpServletRequest();
        a.addHeader("X-Api-Key", "key-a");
        MockHttpServletRequest b = new MockHttpServletRequest();
        b.addHeader("X-Api-Key", "key-b");

        assertThat(interceptor.preHandle(a, response, new Object())).isTrue();
        assertThat(interceptor.preHandle(b, response, new Object())).isTrue();   // bucket independant
        assertThatThrownBy(() -> interceptor.preHandle(a, response, new Object()))
                .isInstanceOf(WebhookRateLimitException.class);
    }
}
