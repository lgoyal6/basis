/* Checks image upload through OCR, durable review and export over local HTTP.
 * Starts the real server with disposable PostgreSQL and installed Tesseract.
 * Uses test approvals only; no human workflow or deployment is implied.
 */
package com.basis.documents.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.web-application-type=servlet", "basis.documents.actor=http-reviewer", "basis.documents.role=REVIEWER"})
@ActiveProfiles({"web", "workbench"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class DocumentHttpWorkflowTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024).withNanoCPUs(1_000_000_000L));
    private static final UUID TENANT = UUID.randomUUID();
    private static final Path ROOT;
    static { try { ROOT = Files.createTempDirectory("basis-http-workflow-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("basis.documents.tenant-id", TENANT::toString);
        r.add("basis.documents.token-digest", () -> "4c5dc9b7708905f77f5e5d16316b5dfb425e68cb326dcd55a860e90a7707031e");
        r.add("basis.documents.object-root", ROOT::toString);
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired ProcessingJobRepository jobs;
    @Autowired DocumentPipelineService pipeline;
    @Autowired java.util.List<DocumentExtractor> extractors;
    @AfterAll static void cleanup() throws Exception {
        try (var paths = Files.walk(ROOT)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    HttpHeaders headers() {
        var headers = new HttpHeaders(); headers.setBearerAuth("test-token"); headers.set("X-Tenant-Id", TENANT.toString()); return headers;
    }
    ResponseEntity<JsonNode> upload(byte[] bytes, String filename, MediaType type, String key) {
        var body = new LinkedMultiValueMap<String, Object>(); body.add("documentKey", "annual-report");
        var file = new ByteArrayResource(bytes) { @Override public String getFilename() { return filename; } };
        var partHeaders = new HttpHeaders(); partHeaders.setContentType(type);
        body.add("file", new HttpEntity<>(file, partHeaders));
        var headers = headers(); headers.setContentType(MediaType.MULTIPART_FORM_DATA); headers.set("Idempotency-Key", key);
        return http.postForEntity("/v1/workbench/documents", new HttpEntity<>(body, headers), JsonNode.class);
    }
    String export() {
        var response = http.exchange("/v1/workbench/documents/export", HttpMethod.GET, new HttpEntity<>(headers()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); return response.getBody();
    }
    @Test void imageUploadRunsLocalOcrReviewAndApprovedExportOverHttp() throws Exception {
        Assumptions.assumeTrue(extractors.stream().anyMatch(e -> e.getClass().getSimpleName().startsWith("Tesseract") && e.supports("image/png", "scan.png")), "installed Tesseract required for actual OCR workflow");
        db.update("insert into doc_tenant(id,name) values (?,?)", TENANT, "http-workflow");
        var image = new BufferedImage(1400, 200, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1400, 200);
        graphics.setColor(Color.BLACK); graphics.setFont(new Font("Monospaced", Font.PLAIN, 44));
        graphics.drawString("REVENUE: 1250 USD 2025-12-31", 30, 100); graphics.dispose();
        var png = new java.io.ByteArrayOutputStream(); ImageIO.write(image, "png", png);
        var receipt = upload(png.toByteArray(), "scan.png", MediaType.IMAGE_PNG, "image");
        assertThat(receipt.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(upload(png.toByteArray(), "scan.png", MediaType.IMAGE_PNG, "image").getBody().path("jobId").asText()).isEqualTo(receipt.getBody().path("jobId").asText());
        assertThat(new DurablePipelineWorker(jobs, "http-test", java.time.Duration.ofSeconds(60), 3).runOnce(TENANT.toString(), pipeline::stagesFor)).isTrue();
        assertThat(db.queryForObject("select status from processing_job where tenant_id=?", String.class, TENANT)).isEqualTo("COMPLETED");
        UUID task = db.queryForObject("select id from review_task where tenant_id=?", UUID.class, TENANT);
        UUID fact = db.queryForObject("select fact_id from review_task where id=?", UUID.class, task);
        assertThat(export()).doesNotContain(fact.toString());
        var headers = headers(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("Idempotency-Key", "approve");
        var body = Map.of("expectedVersion", 0, "action", "APPROVE", "actor", "spoof", "reason", "source checked");
        var approved = http.postForEntity("/v1/workbench/review-tasks/" + task + "/decisions", new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().path("fact").path("value").decimalValue()).isEqualByComparingTo("1250");
        assertThat(export()).contains(fact.toString(), "review_decision_id", receipt.getBody().path("sha256").asText());
        var unauthorized = headers(); unauthorized.set("X-Tenant-Id", UUID.randomUUID().toString());
        assertThat(http.exchange("/v1/workbench/documents/export", HttpMethod.GET, new HttpEntity<>(unauthorized), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(upload("revenue,1300,2025-12-31\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), "new.csv", MediaType.valueOf("text/csv"), "new-source").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(export()).doesNotContain(fact.toString());
        assertThat(db.queryForObject("select actor from review_decision where tenant_id=?", String.class, TENANT)).isEqualTo("http-reviewer");
    }
}
