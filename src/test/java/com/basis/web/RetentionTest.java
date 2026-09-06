package com.basis.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The two retention promises, held against the running service rather than the source.
 *
 * <p>PRIVACY.md says an upload is never written to a database and is gone in two hours. The
 * first is checked here by looking in the database after an upload rather than by reading
 * {@link SessionStore} and believing it. The second is checked at fifteen minutes rather
 * than at the default, because {@code basis.web.session-minutes} is documented as the knob
 * that sets it and a knob that is wired to nothing is a retention policy nobody has.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.main.web-application-type=servlet",
            // Deliberately not the default, so a lifetime that ignores this is visible.
            "basis.web.session-minutes=15"
        })
@AutoConfigureMockMvc
@ActiveProfiles("web")
@org.testcontainers.junit.jupiter.Testcontainers
class RetentionTest {

    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    @org.testcontainers.junit.jupiter.Container
    static org.testcontainers.containers.PostgreSQLContainer<?> postgres =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");

    /** Every table an import would write to, which is every table an upload must not. */
    private static final java.util.List<String> LEDGER_TABLES = java.util.List.of(
            "import_batch", "txn", "posting", "position", "lot", "realized_gain",
            "break_record");

    private static final String HISTORY = "Run Date,Account,Account Number,Action,Symbol,"
            + "Description,Type,Price ($),Quantity,Commission ($),Fees ($),Accrued Interest ($),"
            + "Amount ($),Settlement Date\n"
            + "01/02/2020,Individual,X,ELECTRONIC FUNDS TRANSFER RECEIVED (Cash),\"\","
            + "No Description,Cash,\"\",\"\",\"\",\"\",\"\",5000,01/02/2020\n"
            + "01/03/2020,Individual,X,YOU BOUGHT APPLE INC (AAPL) (Cash),AAPL,APPLE INC,Cash,"
            + "300.00,10,\"\",\"\",\"\",-3000,01/06/2020\n";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionStore sessions;

    @Test
    @DisplayName("the configured retention is the retention the store and the cookie use")
    void theConfiguredLifetimeIsTheRealOne() throws Exception {
        Cookie session = mvc.perform(multipart("/check").file(history()))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getCookie(SessionCookie.NAME);

        assertThat(session).isNotNull();
        assertThat(session.getMaxAge())
                .as("a cookie outliving the data it points at is a confusing empty page later")
                .isEqualTo(15 * 60);
        assertThat(sessions.expiryOf(idOf(session)))
                .as("a documented setting that nothing reads is a retention policy nobody has")
                .isPresent()
                .hasValueSatisfying(expiry -> assertThat(expiry)
                        .isBefore(java.time.Instant.now().plus(java.time.Duration.ofMinutes(16))));
    }

    @Test
    @DisplayName("the privacy page quotes the retention that is configured, not one from the source")
    void thePrivacyPageQuotesTheConfiguredRetention() throws Exception {
        String page = mvc.perform(get("/privacy"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page)
                .as("the page a stranger is asked to believe has to say what this deploy does")
                .contains("15 minutes")
                .doesNotContain("2 hours");
    }

    @Test
    @DisplayName("an upload reaches no table, which is the claim the privacy page leads with")
    void nothingUploadedReachesTheDatabase() throws Exception {
        Cookie session = mvc.perform(multipart("/check").file(history()))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getCookie(SessionCookie.NAME);
        mvc.perform(get("/breaks").cookie(session)).andExpect(status().isOk());
        mvc.perform(get("/breaks.csv").cookie(session)).andExpect(status().isOk());

        for (String table : LEDGER_TABLES) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class))
                    .as(table + " holds something after an upload, and it was promised it would not")
                    .isZero();
        }
    }

    private static String idOf(Cookie session) {
        return session.getValue().substring(0, session.getValue().lastIndexOf('.'));
    }

    private static MockMultipartFile history() {
        return new MockMultipartFile("history", "history.csv", "text/csv",
                HISTORY.getBytes(StandardCharsets.UTF_8));
    }
}
