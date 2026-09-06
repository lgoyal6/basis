package com.basis.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * Regressions for the defects Schemathesis found and {@link ApiBoundaryTest} did not.
 *
 * <p>Each test names the check that produced it. The invocation is
 * {@code scripts/schemathesis.sh}, which runs the tool against {@code docs/openapi.json} and a
 * real {@code basis serve} over a socket. Every test here fails on the parent commit.
 *
 * <p><b>Why the existing corpus missed them, and it is not a weak file.</b>
 * {@link ApiBoundaryTest} builds its cases through MockMvc's request builders, which assemble
 * a well formed multipart request for you. So it can vary the <em>contents</em> of a part and
 * never the <em>framing</em> around it: it cannot send a body whose closing boundary is
 * missing, cannot send the same field name twice, and cannot send a POST to {@code /check}
 * with no body at all, because the builder always produces one. Those three shapes are where
 * all four defects lived. It also stops checking a response the moment the status is 5xx, so
 * its own leak detector never ran over the one page that leaked.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("web")
@org.testcontainers.junit.jupiter.Testcontainers
class ApiSchemathesisRegressionTest {

    static {
        ApiContract.registerViewControllers();
    }

    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    @org.testcontainers.junit.jupiter.Container
    static org.testcontainers.containers.PostgreSQLContainer<?> postgres =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");

    private static final String HEADER = "Run Date,Account,Account Number,Action,Symbol,"
            + "Description,Type,Price ($),Quantity,Commission ($),Fees ($),Accrued Interest ($),"
            + "Amount ($),Settlement Date";

    @Autowired
    private MockMvc mvc;

    // -- check: positive_data_acceptance / negative_data_rejection --------------

    @Test
    @DisplayName("a POST to /check with no body at all is refused rather than answered 200")
    void aRequestWithNoBodyIsNotAccepted() throws Exception {
        // Schemathesis `negative_data_rejection`: `curl -X POST /check` with nothing after it.
        // The contract requires a multipart body, and the answer was 200 OK with the upload
        // form. Every refusal this app produced answered 200, so nothing that reads a status
        // line - a monitor, a cache, a generated client, the contract itself - could tell a
        // refused upload from a computed answer.
        MvcResult result = mvc.perform(post("/check")).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("a request the contract requires a body for was accepted")
                .isEqualTo(400);
        assertThat(result.getResponse().getContentType()).startsWith("text/html");
        assertThat(result.getResponse().getContentAsString())
                .as("the page is still the page: a person sees the form and a sentence")
                .contains("basis");
    }

    // -- check: not_a_server_error + status_code_conformance --------------------

    @Test
    @DisplayName("a multipart body that stops mid-stream is a 400 page, not a 500")
    void aTruncatedMultipartBodyIsNotAServerError() throws Exception {
        // Schemathesis `not_a_server_error`, generated body: a boundary line followed by the
        // closing marker and no part. Tomcat's parser raises MalformedStreamException before
        // dispatch picks a handler, so no @ExceptionHandler on the controller could see it,
        // nothing resolved it, and the caller got 500 with Spring's application/json error
        // body from a service that declares no JSON on any operation.
        byte[] truncated = ("--b\r\nNone--b--\r\n").getBytes(StandardCharsets.UTF_8);
        MvcResult result = mvc.perform(post("/check")
                        .contentType("multipart/form-data; boundary=b")
                        .content(truncated))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentType())
                .as("basis has no JSON surface; the framework's error body is not basis' answer")
                .startsWith("text/html");
    }

    @Test
    @DisplayName("the same form field sent twice is a 400 page, not a 500 that names the stack")
    void aRepeatedFieldIsNotAServerError() throws Exception {
        // Schemathesis `not_a_server_error`, generated body: `broker` twice. Spring binds the
        // pair as an ArrayList to a String parameter and throws
        // MethodArgumentTypeMismatchException, which the RuntimeException catch-all turned
        // into a 500 whose page carried "Failed to convert value of type 'java.util.ArrayList'
        // ... [@org.springframework.web.bind.annotation.RequestParam java.lang.String]".
        //
        // ApiBoundaryTest greps bodies for exactly those markers. It could not have got here:
        // it stops checking at 5xx, and MockMvc's multipart builder writes each field once.
        MvcResult result = mvc.perform(multipart("/check")
                        .file(history())
                        .param("broker", "fidelity")
                        .param("broker", "fidelity"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
                .as("the refusal must not describe the handler's signature to a stranger")
                .doesNotContain("java.lang.")
                .doesNotContain("org.springframework.web")
                .doesNotContain("ArrayList");
    }

    @Test
    @DisplayName("the unexpected-failure page says nothing about the exception that caused it")
    void theCatchAllPageCarriesNoExceptionText() throws Exception {
        // The general form of the defect above. The catch-all put failure.getMessage() on the
        // page, which is an unconditional disclosure channel: nobody chooses what goes through
        // it and nobody reviews what comes out. The one input that reached it was generated,
        // not written.
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/basis/web/UploadController.java"));
        String handler = source.substring(source.indexOf("public String unexpected("));
        handler = handler.substring(0, handler.indexOf("\n    }"));

        assertThat(handler)
                .as("the unexpected-failure handler puts the exception's own message on the page")
                .doesNotContain("failure.getMessage()");
        assertThat(handler)
                .as("and it still records the cause where an operator can find it")
                .contains("log.error");
    }

    // -- check: status_code_conformance, second round ---------------------------

    @Test
    @DisplayName("an unparseable date on /resolve is a refused choice, not an unexpected failure")
    void anUnreadableDateIsARefusedChoiceRatherThanA500() throws Exception {
        // Not from a Schemathesis run directly: it is what fixing the 200 exposed. The date
        // parse sat outside the try that catches a refused choice, so DateTimeParseException
        // went to the catch-all. ApiBoundaryTest already had two inputs that landed there -
        // "unparseable date" and "every field repeated" - and could not see it while the
        // catch-all answered 200. The moment that answered the 500 it really was, both
        // surfaced as 5xx violations in the corpus that had been sending them all along.
        MvcResult upload = mvc.perform(multipart("/check").file(history())).andReturn();
        jakarta.servlet.http.Cookie session = upload.getResponse().getCookie(SessionCookie.NAME);
        assertThat(session).as("the upload has to have opened a session").isNotNull();

        MvcResult result = mvc.perform(post("/resolve").cookie(session)
                        .param("kind", "split").param("symbol", "AAPL")
                        .param("detail", "4:1").param("on", "not-a-date"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("a client sending a date basis cannot read is expected, not unexpected")
                .isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/breaks?refused=1");
    }

    // -- check: positive_data_acceptance ----------------------------------------

    @Test
    @DisplayName("the contract says a history file needs at least one byte, because it does")
    void theNonEmptyRuleIsInTheContractAndNotOnlyInTheCode() throws Exception {
        // Schemathesis `positive_data_acceptance`: it generated a zero-byte `history` part,
        // which the schema permitted, and reported basis as rejecting a schema-compliant
        // request. The constraint is real either way, so it is published rather than dropped.
        //
        // The tool still generates the empty part after this change - it does not apply
        // minLength to a `format: binary` string - so the report stands in the run output and
        // is recorded there as a limitation. What this test pins is the half that is in our
        // hands: the document now states the rule the code enforces.
        var body = ApiContract.readArtifact()
                .get("paths").get("/check").get("post").get("requestBody")
                .get("content").get("multipart/form-data").get("schema")
                .get("properties").get("history");
        assertThat(body.get("minLength").asInt()).isEqualTo(1);

        assertThat(mvc.perform(multipart("/check")
                        .file(new MockMultipartFile("history", "empty.csv", "text/csv",
                                new byte[0])))
                .andReturn().getResponse().getStatus())
                .isEqualTo(400);
    }

    private MockMultipartFile history() {
        return new MockMultipartFile("history", "history.csv", "text/csv",
                (HEADER + "\n08/31/2020,Individual,X1,YOU BOUGHT,AAPL,APPLE INC,Cash,100.00,"
                        + "10,0,0,0,-1000.00,09/02/2020\n").getBytes(StandardCharsets.UTF_8));
    }
}
