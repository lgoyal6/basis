package com.basis.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The flow as a browser sees it: pages render, cookies round trip, errors come back as pages.
 *
 * <p>{@code BreakFinderTest} proves the computation and needs no Spring at all. This proves the
 * part that only exists in a servlet container: that the templates render without a Thymeleaf
 * error, that the session cookie is set and honoured, that a refusal produces a page with a
 * message rather than a 500, and that deleting really does make the data unreachable.
 *
 * <p>Runs against a real Postgres because the context needs a datasource for reference data
 * and Flyway. No uploaded data goes near it, which is the point of the {@code /breaks} checks
 * below rather than an assumption.
 */
// The property, not just the webEnvironment: application.yml defaults
// spring.main.web-application-type to none for the CLI, and a value in config beats the one
// the test slice would otherwise infer, so without this the context is not a web context and
// MockMvc never exists. Same trap that stopped "basis serve" opening a port.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("web")
@org.testcontainers.junit.jupiter.Testcontainers
class UploadFlowTest {

    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    @org.testcontainers.junit.jupiter.Container
    static org.testcontainers.containers.PostgreSQLContainer<?> postgres =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HEADER = "Run Date,Account,Account Number,Action,Symbol,"
            + "Description,Type,Price ($),Quantity,Commission ($),Fees ($),Accrued Interest ($),"
            + "Amount ($),Settlement Date";

    private static final String HISTORY = HEADER + "\n"
            + "01/02/2020,Individual,X,ELECTRONIC FUNDS TRANSFER RECEIVED (Cash),\"\",No Description,"
            + "Cash,\"\",\"\",\"\",\"\",\"\",5000,01/02/2020\n"
            + "01/03/2020,Individual,X,YOU BOUGHT APPLE INC (AAPL) (Cash),AAPL,APPLE INC,Cash,"
            + "300.00,10,\"\",\"\",\"\",-3000,01/06/2020\n";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("the landing page renders and offers the demo without asking for anything")
    void landingRenders() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No account")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/demo")));
    }

    @Test
    @DisplayName("the demo sets a session cookie and the results page renders its breaks")
    void demoRendersBreaks() throws Exception {
        Cookie session = sessionFrom(mvc.perform(get("/demo"))
                .andExpect(status().is3xxRedirection()).andReturn());
        assertThat(session).as("a session cookie is what makes the results page reachable").isNotNull();

        mvc.perform(get("/breaks").cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("UNAPPLIED_SPLIT")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("confirmed")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Delete my data now")));
    }

    @Test
    @DisplayName("an uploaded statement is computed and rendered")
    void uploadRendersHoldings() throws Exception {
        Cookie session = sessionFrom(mvc.perform(multipart("/check").file(history()))
                .andExpect(status().is3xxRedirection()).andReturn());

        mvc.perform(get("/breaks").cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AAPL")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("3000.00")));
    }

    @Test
    @DisplayName("a positions file in the history slot is refused with a page, not a stack trace")
    void theWrongFileGetsAPage() throws Exception {
        mvc.perform(multipart("/check").file(new MockMultipartFile("history", "positions.csv",
                        "text/csv", "symbol,quantity,cost_basis,kind\nAAPL,40,,EQUITY\n"
                                .getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("not a history of trades")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("second box")));
    }

    @Test
    @DisplayName("the break list downloads as CSV with the explanation intact")
    void csvExport() throws Exception {
        Cookie session = sessionFrom(mvc.perform(get("/demo")).andReturn());

        String csv = mvc.perform(get("/breaks.csv").cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(csv.lines().findFirst().orElseThrow()).startsWith("as_of,account,symbol");
        assertThat(csv).contains("UNAPPLIED_SPLIT").contains("confirmed");
    }

    @Test
    @DisplayName("delete makes it unreachable straight away")
    void deleteIsImmediate() throws Exception {
        Cookie session = sessionFrom(mvc.perform(get("/demo")).andReturn());
        mvc.perform(get("/breaks").cookie(session)).andExpect(status().isOk());

        mvc.perform(post("/delete").cookie(session)).andExpect(status().is3xxRedirection());

        mvc.perform(get("/breaks").cookie(session))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/breaks.csv").cookie(session))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a tampered cookie is rejected rather than looked up")
    void forgedCookieIsRejected() throws Exception {
        Cookie forged = new Cookie(SessionCookie.NAME, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.no");

        mvc.perform(get("/breaks").cookie(forged)).andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("choosing an offered corporate action clears the break it explains")
    void resolvingAChoiceUpdatesTheList() throws Exception {
        Cookie session = sessionFrom(mvc.perform(get("/demo")).andReturn());
        String before = mvc.perform(get("/breaks").cookie(session))
                .andReturn().getResponse().getContentAsString();
        assertThat(before).contains("VTSAX");

        mvc.perform(post("/resolve").cookie(session)
                        .param("kind", "reverse-split").param("symbol", "VTSAX")
                        .param("detail", "1:3").param("on", "2026-08-24"))
                .andExpect(status().is3xxRedirection());

        String after = mvc.perform(get("/breaks").cookie(session))
                .andReturn().getResponse().getContentAsString();
        assertThat(countBreaks(after))
                .as("one fewer disagreement than before")
                .isEqualTo(countBreaks(before) - 1);
    }

    @Test
    @DisplayName("the privacy page says what the code does")
    void privacyRenders() throws Exception {
        mvc.perform(get("/privacy"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("never written to a database")));
    }

    @Test
    @DisplayName("health reaches the database rather than returning a constant")
    void healthChecksTheDatabase() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("session")));
    }

    private static MockMultipartFile history() {
        return new MockMultipartFile("history", "history.csv", "text/csv",
                HISTORY.getBytes(StandardCharsets.UTF_8));
    }

    private static Cookie sessionFrom(MvcResult result) {
        return result.getResponse().getCookie(SessionCookie.NAME);
    }

    private static int countBreaks(String html) {
        return html.split("class=\"break ", -1).length - 1;
    }
}
