package com.supportiq.backend.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests d'integration de l'import structure (S2-J1) sur PostgreSQL reel :
 * upload CSV/XLSX par un ADMIN -> import AWAITING_VALIDATION + apercu ; CSV malforme -> erreurs ;
 * un AGENT est refuse (403).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ImportIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.security.jwt.secret",
                () -> "test-secret-supportiq-0123456789-abcdefghijklmnop");
        registry.add("app.bootstrap.admin.email", () -> "admin@supportiq.local");
        registry.add("app.bootstrap.admin.password", () -> "admin1234");
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void admin_importsCsv_getsPreviewAndAwaitingValidation() {
        String csv = "external_ref,customer_email,subject\n"
                + "TCK-1,a@x.com,Bonjour\n"
                + "TCK-2,b@x.com,Hello\n";
        ResponseEntity<Map> resp = upload(csv.getBytes(StandardCharsets.UTF_8), "tickets.csv", adminToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("fileType")).isEqualTo("CSV");
        assertThat(resp.getBody().get("status")).isEqualTo("AWAITING_VALIDATION");
        assertThat(resp.getBody().get("totalRows")).isEqualTo(2);
        assertThat((java.util.List<?>) resp.getBody().get("headers")).hasSize(3);
        assertThat(resp.getBody().get("errorCount")).isEqualTo(0);
    }

    @Test
    void admin_importsXlsx_isParsed() throws Exception {
        byte[] xlsx = buildXlsx();
        ResponseEntity<Map> resp = upload(xlsx, "tickets.xlsx", adminToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("fileType")).isEqualTo("XLSX");
        assertThat(resp.getBody().get("totalRows")).isEqualTo(2);
    }

    @Test
    void malformedCsv_reportsRowErrors() {
        String csv = "external_ref,customer_email,subject\n"
                + "TCK-1,a@x.com,Bonjour\n"
                + "TCK-2,b@x.com\n"; // ligne a 2 colonnes au lieu de 3
        ResponseEntity<Map> resp = upload(csv.getBytes(StandardCharsets.UTF_8), "bad.csv", adminToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((Integer) resp.getBody().get("errorCount")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void agent_cannotImport_isForbidden() {
        String adminToken = adminToken();
        rest.exchange("/api/auth/register", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "imp-agent@x.com", "password", "password123",
                        "fullName", "Agent", "role", "AGENT"), jsonBearer(adminToken)), Map.class);

        String agentToken = tokenFor("imp-agent@x.com", "password123");
        ResponseEntity<Map> resp = upload("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8),
                "x.csv", agentToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- helpers ---------------------------------------------------------------

    private ResponseEntity<Map> upload(byte[] content, String filename, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);
        return rest.postForEntity("/api/imports", new HttpEntity<>(body, headers), Map.class);
    }

    private byte[] buildXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("tickets");
            String[][] data = {
                {"external_ref", "customer_email", "subject"},
                {"TCK-1", "a@x.com", "Bonjour"},
                {"TCK-2", "b@x.com", "Hello"},
            };
            for (int r = 0; r < data.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < data[r].length; c++) {
                    row.createCell(c).setCellValue(data[r][c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private String adminToken() {
        return tokenFor("admin@supportiq.local", "admin1234");
    }

    private String tokenFor(String email, String password) {
        ResponseEntity<Map> resp = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("email", email, "password", password), jsonHeaders()), Map.class);
        return (String) resp.getBody().get("accessToken");
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders jsonBearer(String token) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
