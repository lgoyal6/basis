package com.basis.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Two strangers uploading at the same time, and neither one reaching the other.
 *
 * <p>There are no accounts here, so the whole of the access control is one signed cookie
 * holding one unguessable id. That makes it worth proving rather than assuming: that two
 * uploads stay apart, that one person's delete button does not touch anyone else's data,
 * that a cookie is checked against the store on every request rather than trusted because
 * it verifies, and that a request arriving without the cookie changes nothing.
 *
 * <p>Every check here has a matching one against the legitimate flow, because a session
 * layer that refuses everybody is not secure, it is broken.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("web")
@org.testcontainers.junit.jupiter.Testcontainers
class SessionIsolationTest {

    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    @org.testcontainers.junit.jupiter.Container
    static org.testcontainers.containers.PostgreSQLContainer<?> postgres =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HEADER = "Run Date,Account,Account Number,Action,Symbol,"
            + "Description,Type,Price ($),Quantity,Commission ($),Fees ($),Accrued Interest ($),"
            + "Amount ($),Settlement Date";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("two uploads at once stay apart, and each cookie reaches only its own")
    void twoStrangersDoNotSeeEachOther() throws Exception {
        Cookie alice = upload("AAPL");
        Cookie bob = upload("MSFT");

        assertThat(alice.getValue()).isNotEqualTo(bob.getValue());
        assertThat(breaksFor(alice)).contains("AAPL").doesNotContain("MSFT");
        assertThat(breaksFor(bob)).contains("MSFT").doesNotContain("AAPL");
    }

    @Test
    @DisplayName("one person's delete removes their upload and nobody else's")
    void deletingIsNotContagious() throws Exception {
        Cookie alice = upload("AAPL");
        Cookie bob = upload("MSFT");

        mvc.perform(post("/delete").cookie(alice)).andExpect(status().is3xxRedirection());

        mvc.perform(get("/breaks").cookie(alice))
                .andExpect(status().is3xxRedirection());
        assertThat(breaksFor(bob))
                .as("deleting is scoped to the session that asked, not to the store")
                .contains("MSFT");
    }

    @Test
    @DisplayName("a cookie is checked against the store on every request, not trusted for verifying")
    void everyRequestReChecks() throws Exception {
        Cookie alice = upload("AAPL");
        mvc.perform(get("/breaks").cookie(alice)).andExpect(status().isOk());

        mvc.perform(post("/delete").cookie(alice)).andExpect(status().is3xxRedirection());

        // The cookie is still perfectly well signed. What it points at is gone, and every
        // entry point has to find that out for itself rather than one of them checking at
        // the door and the rest assuming.
        mvc.perform(get("/breaks").cookie(alice)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/breaks.csv").cookie(alice)).andExpect(status().isNotFound());
        mvc.perform(post("/resolve").cookie(alice)
                        .param("kind", "split").param("symbol", "AAPL")
                        .param("detail", "4:1").param("on", "2020-08-31"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/?expired=1"));
    }

    @Test
    @DisplayName("one person's signature does not carry another person's id")
    void aSignatureDoesNotTravel() throws Exception {
        Cookie alice = upload("AAPL");
        Cookie bob = upload("MSFT");
        String aliceId = alice.getValue().substring(0, alice.getValue().lastIndexOf('.'));
        String bobSignature = bob.getValue().substring(bob.getValue().lastIndexOf('.') + 1);

        Cookie spliced = new Cookie(SessionCookie.NAME, aliceId + "." + bobSignature);

        // A valid signature for some other id is not a valid cookie.
        mvc.perform(get("/breaks").cookie(spliced))
                .andExpect(status().is3xxRedirection());
        // And the real one still works, so this is not a check that refuses everything.
        mvc.perform(get("/breaks").cookie(alice)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a request with no cookie changes nothing, which is what a cross site post is")
    void aRequestWithoutTheCookieDoesNothing() throws Exception {
        Cookie alice = upload("AAPL");

        // There is no CSRF token on this form. The defence is that the cookie is SameSite
        // Lax, so a post from another origin arrives without it. This is that request.
        mvc.perform(post("/delete")).andExpect(status().is3xxRedirection());

        assertThat(breaksFor(alice))
                .as("a delete that carried no session must not have deleted a session")
                .contains("AAPL");
    }

    @Test
    @DisplayName("the session cookie is HttpOnly and SameSite Lax, and Secure only over HTTPS")
    void theCookieCarriesItsFlags() throws Exception {
        Cookie overHttp = upload("AAPL");
        assertThat(overHttp.isHttpOnly())
                .as("script must not be able to read the one thing that reaches the upload")
                .isTrue();
        assertThat(overHttp.getAttribute("SameSite"))
                .as("without a CSRF token this is the whole cross site defence")
                .isEqualTo("Lax");
        assertThat(overHttp.getSecure())
                .as("not unconditional, because that would break localhost")
                .isFalse();

        Cookie overHttps = sessionFrom(mvc.perform(multipart("/check").file(history("AAPL")).secure(true))
                .andExpect(status().is3xxRedirection()).andReturn());
        assertThat(overHttps.getSecure())
                .as("a request that arrived over HTTPS gets a cookie that will not leave it")
                .isTrue();
    }

    @Test
    @DisplayName("a broker name that is a path is refused with a page and tells a stranger nothing")
    void theBrokerFieldIsNotAPath() throws Exception {
        String page = mvc.perform(multipart("/check").file(history("AAPL"))
                        .param("broker", "../../gradle/wrapper/gradle-wrapper"))
                // 400 rather than 200: see UploadFlowTest.theWrongFileGetsAPage. What this
                // test is about - that the refusal names no file on disk - is below and
                // unchanged.
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(page)
                .as("naming the file it went looking for is the disclosure, not the refusal")
                .doesNotContain("gradle-wrapper")
                .doesNotContain(".properties");
        assertThat(page).contains("no profile for the broker");
        // The named broker still works, so the check did not just close the field.
        assertThat(sessionFrom(mvc.perform(multipart("/check").file(history("AAPL"))
                        .param("broker", "fidelity"))
                .andExpect(status().is3xxRedirection()).andReturn())).isNotNull();
    }

    private Cookie upload(String symbol) throws Exception {
        return sessionFrom(mvc.perform(multipart("/check").file(history(symbol)))
                .andExpect(status().is3xxRedirection()).andReturn());
    }

    private String breaksFor(Cookie session) throws Exception {
        return mvc.perform(get("/breaks").cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static MockMultipartFile history(String symbol) {
        String csv = HEADER + "\n"
                + "01/02/2020,Individual,X,ELECTRONIC FUNDS TRANSFER RECEIVED (Cash),\"\","
                + "No Description,Cash,\"\",\"\",\"\",\"\",\"\",5000,01/02/2020\n"
                + "01/03/2020,Individual,X,YOU BOUGHT " + symbol + " (Cash)," + symbol + ","
                + symbol + " INC,Cash,300.00,10,\"\",\"\",\"\",-3000,01/06/2020\n";
        return new MockMultipartFile("history", "history.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
    }

    private static Cookie sessionFrom(MvcResult result) {
        return result.getResponse().getCookie(SessionCookie.NAME);
    }
}
