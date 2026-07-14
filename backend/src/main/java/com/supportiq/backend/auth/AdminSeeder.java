package com.supportiq.backend.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Amorce idempotente du premier ADMIN depuis la configuration (app.bootstrap.admin.*).
 * Ne fait rien si les variables ne sont pas fournies (cas prod par defaut) ou si le compte existe deja.
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminFullName;

    public AdminSeeder(UserRepository users, PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap.admin.email:}") String adminEmail,
            @Value("${app.bootstrap.admin.password:}") String adminPassword,
            @Value("${app.bootstrap.admin.full-name:Administrateur SupportIQ}") String adminFullName) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminFullName = adminFullName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(adminEmail) || !StringUtils.hasText(adminPassword)) {
            return; // seeding desactive
        }
        if (users.existsByEmail(adminEmail)) {
            return; // idempotent
        }
        User admin = User.builder()
                .email(adminEmail)
                .passwordHash(passwordEncoder.encode(adminPassword))
                .fullName(adminFullName)
                .role(Role.ADMIN)
                .build();
        users.save(admin);
        log.info("Compte ADMIN initial cree : {}", adminEmail);
    }
}
