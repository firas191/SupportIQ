package com.supportiq.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Point d'entree du plan de controle.
 *
 * <p>{@code @EnableScheduling} active le declenchement automatique du digest hebdomadaire (S6-J4).
 * Le planificateur de Spring suffit ici : la persistance des declenchements, la coordination
 * multi-instance et le rattrapage sont portes par la contrainte {@code UNIQUE(week_start)} de la
 * migration V12, pas par l'ordonnanceur. Voir {@code DigestScheduler} pour l'argument complet.
 */
@SpringBootApplication
@EnableScheduling
public class SupportiqBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupportiqBackendApplication.class, args);
    }
}
