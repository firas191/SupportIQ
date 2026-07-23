package com.supportiq.backend.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook d'ingestion temps reel (S2-J4). Hors chaine JWT : authentifie par cle API + HMAC.
 *
 * <p>Le corps est recu en {@code byte[]} (et non desérialisé par Spring) pour que la signature HMAC
 * porte sur les octets exacts signés par l'appelant — re-serialiser un objet Jackson ne garantirait
 * pas le meme flux d'octets. 202 Accepted : le ticket est cree et l'analyse IA suit de facon asynchrone.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final WebhookService service;

    public WebhookController(WebhookService service) {
        this.service = service;
    }

    @PostMapping("/tickets")
    public ResponseEntity<WebhookResponse> ingest(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody(required = false) byte[] rawBody) {
        WebhookResponse response = service.ingest(apiKey, signature, rawBody);
        HttpStatus status = "DUPLICATE".equals(response.result()) ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }
}
