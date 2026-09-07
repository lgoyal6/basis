package com.basis.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** Runs the public demo in Chrome and inspects the browser's own output and network log. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.main.web-application-type=servlet",
            "basis.fmp.key=c06-browser-config-secret"
        })
@ActiveProfiles("web")
@org.testcontainers.junit.jupiter.Testcontainers
class BrowserSessionDiagnosticsTest {

    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    @org.testcontainers.junit.jupiter.Container
    static org.testcontainers.containers.PostgreSQLContainer<?> postgres =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");

    private static final String CONFIG_SECRET = "c06-browser-config-secret";

    @LocalServerPort
    private int port;

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("a real browser completes the demo without putting configuration secrets in diagnostics")
    void legitimateDemoKeepsSecretsOutOfBrowserDiagnostics() throws Exception {
        BrowserCapture capture = browse("/demo", "legitimate");

        assertThat(capture.output())
                .as("Chrome followed the demo redirect and rendered the actual results page")
                .contains("UNAPPLIED_SPLIT")
                .contains("Delete my data now");
        assertThat(capture.networkLog().length())
                .as("Chrome produced a real network event log")
                .isGreaterThan(1_000);
        assertThat(capture.networkLog())
                .as("the network log contains the legitimate demo request and its redirected result")
                .contains("/demo")
                .contains("/breaks");
        assertSecretsAbsent(List.of(capture.output(), capture.networkLog()), List.of(CONFIG_SECRET));
    }

    @Test
    @DisplayName("the browser diagnostic gate detects a secret planted in a request URL")
    void seededNetworkLeakIsDetected() {
        String planted = "seeded-browser-secret-for-negative-control";
        String plantedNetworkRecord = "request:GET:http://127.0.0.1/?probe=" + planted;

        assertThatThrownBy(() -> assertSecretsAbsent(
                        List.of(plantedNetworkRecord), List.of(planted)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("browser diagnostic captured a protected value");
    }

    private BrowserCapture browse(String path, String name) throws Exception {
        Path profile = Files.createDirectory(temporaryDirectory.resolve(name + "-profile"));
        Path output = temporaryDirectory.resolve(name + "-browser.log");
        Path network = temporaryDirectory.resolve(name + "-network.json");
        String url = "http://127.0.0.1:" + port + path;

        Process process = new ProcessBuilder(
                        chrome(),
                        "--headless=new",
                        "--disable-gpu",
                        "--no-first-run",
                        "--no-default-browser-check",
                        "--disable-background-networking",
                        "--disable-component-update",
                        "--disable-sync",
                        "--metrics-recording-only",
                        "--user-data-dir=" + profile,
                        "--enable-logging=stderr",
                        "--v=1",
                        "--log-net-log=" + network,
                        "--net-log-capture-mode=Default",
                        "--dump-dom",
                        url)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();

        boolean finished = process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } else {
            assertThat(process.exitValue()).as("headless Chrome exit code").isZero();
        }
        return new BrowserCapture(Files.readString(output), Files.readString(network));
    }

    private static String chrome() {
        List<Path> candidates = List.of(
                Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
                Path.of("/usr/bin/google-chrome"),
                Path.of("/usr/bin/chromium"),
                Path.of("/usr/bin/chromium-browser"));
        return candidates.stream()
                .filter(Files::isExecutable)
                .findFirst()
                .map(Path::toString)
                .orElseThrow(() -> new IllegalStateException(
                        "Chrome or Chromium is required for BrowserSessionDiagnosticsTest"));
    }

    private static void assertSecretsAbsent(List<String> records, List<String> secrets) {
        String joined = String.join("\n", records);
        for (String secret : secrets) {
            if (joined.contains(secret)) {
                throw new AssertionError("browser diagnostic captured a protected value: " + secret);
            }
        }
    }

    private record BrowserCapture(String output, String networkLog) {}
}
