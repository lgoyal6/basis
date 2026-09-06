package com.basis.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Whose object is it, asked with generated identities rather than with two hand-picked ones.
 *
 * <p>{@link SessionIsolationTest} establishes that two uploads stay apart, that a delete is
 * scoped to the session that asked, that revocation is rechecked at every entry point and that
 * one person's signature does not carry another person's id. Those are the fixtures this builds
 * on and it does not repeat them.
 *
 * <p>What is added here is the part a pair of well-formed cookies cannot reach. Every
 * single-character mutation of a real cookie is generated and presented, in both halves of the
 * value, along with truncations, extensions and separator moves, at every entry point that
 * takes an object. Three things then have to hold, and the third is the one usually missed:
 * the other person's data never appears; nothing of theirs changes; and the refusal is byte
 * for byte the same whether the id names a real object or nothing at all, so a stranger cannot
 * use the difference to learn that a session exists.
 *
 * <p>The last test is a drift gate rather than a property: it works out by measurement which
 * operations are identity-bearing and fails if one appears that this file does not cover.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("web")
@org.testcontainers.junit.jupiter.Testcontainers
class ObjectOwnershipTest {

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

    private static final long SEED = 20260905L;

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("no mutation of one person's cookie reaches the other person's object")
    void mutatedIdentitiesReachNothing() throws Exception {
        Cookie alice = upload("AAPL");
        Cookie bob = upload("MSFT");
        List<String> mutations = mutationsOf(alice.getValue());

        assertThat(mutations)
                .as("a corpus that generated nothing would pass this test without trying")
                .hasSizeGreaterThan(100);

        List<String> reached = new ArrayList<>();
        for (String value : mutations) {
            if (value.equals(alice.getValue())) {
                continue;
            }
            Cookie forged = new Cookie(SessionCookie.NAME, value);
            String page = body(get("/breaks").cookie(forged));
            String csv = body(get("/breaks.csv").cookie(forged));
            if (page.contains("AAPL") || csv.contains("AAPL")) {
                reached.add(value);
            }
        }
        assertThat(reached)
                .as("these mutated cookie values were served Alice's holdings")
                .isEmpty();

        // And the two genuine cookies still work, so this is not a session layer that refuses
        // everybody. A test that only proves refusal proves nothing.
        assertThat(body(get("/breaks").cookie(alice))).contains("AAPL").doesNotContain("MSFT");
        assertThat(body(get("/breaks").cookie(bob))).contains("MSFT").doesNotContain("AAPL");
    }

    @Test
    @DisplayName("the refusal does not say whether the object behind an id exists")
    void refusalIsTheSameForARealIdAndAnInventedOne() throws Exception {
        Cookie alice = upload("AAPL");
        String realId = alice.getValue().substring(0, alice.getValue().lastIndexOf('.'));

        Cookie realIdWrongSignature =
                new Cookie(SessionCookie.NAME, realId + ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        Cookie inventedIdWrongSignature = new Cookie(SessionCookie.NAME,
                "0123456789abcdef0123456789abcdef.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        Cookie noCookieAtAll = null;

        for (Function<Cookie, RequestBuilder> entryPoint : entryPointsThatTakeAnObject()) {
            String withRealId = describe(entryPoint.apply(realIdWrongSignature));
            String withInventedId = describe(entryPoint.apply(inventedIdWrongSignature));
            String withNone = describe(entryPoint.apply(noCookieAtAll));

            assertThat(withRealId)
                    .as("a signature failure over an id that exists must look exactly like one"
                            + " over an id that does not, or the difference is an oracle for"
                            + " which ids are live")
                    .isEqualTo(withInventedId)
                    .isEqualTo(withNone);
        }

        assertThat(body(get("/breaks").cookie(alice)))
                .as("and Alice is still there, so the four comparisons above were not all"
                        + " comparing an app that had stopped working")
                .contains("AAPL");
    }

    @Test
    @DisplayName("no generated identity can change or destroy another person's object")
    void mutatedIdentitiesWriteNothing() throws Exception {
        Cookie alice = upload("AAPL");
        String before = body(get("/breaks.csv").cookie(alice));
        assertThat(before).as("the object has to have content for its being unchanged to mean"
                + " anything").contains("AAPL").hasSizeGreaterThan(100);

        Random random = new Random(SEED);
        List<String> mutations = mutationsOf(alice.getValue());
        int forgedWrites = 0;
        for (String value : mutations) {
            if (value.equals(alice.getValue())) {
                continue;
            }
            forgedWrites += 2;
            Cookie forged = new Cookie(SessionCookie.NAME, value);
            mvc.perform(post("/delete").cookie(forged));
            mvc.perform(post("/resolve").cookie(forged)
                    .param("kind", random.nextBoolean() ? "split" : "reverse")
                    .param("symbol", "AAPL").param("detail", "4:1")
                    .param("on", "2020-08-31"));
        }

        assertThat(body(get("/breaks.csv").cookie(alice)))
                .as("after " + forgedWrites + " forged writes, Alice's export has to be the"
                        + " same bytes it was")
                .isEqualTo(before);

        // The control: Alice's own delete does work, so "unchanged" above is not just an app
        // in which nothing can be changed by anyone.
        mvc.perform(post("/delete").cookie(alice));
        assertThat(body(get("/breaks.csv").cookie(alice))).isEqualTo("no session\n");
    }

    @Test
    @DisplayName("two identities in one request are never merged")
    void twoCookiesDoNotCombine() throws Exception {
        Cookie alice = upload("AAPL");
        Cookie bob = upload("MSFT");

        for (Cookie[] order : List.of(new Cookie[] {alice, bob}, new Cookie[] {bob, alice})) {
            String page = body(get("/breaks").cookie(order));
            assertThat(page.contains("AAPL") && page.contains("MSFT"))
                    .as("one request answered with both people's holdings")
                    .isFalse();
            assertThat(page.contains("AAPL") || page.contains("MSFT"))
                    .as("and it answered with somebody's, rather than falling over")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("every operation that behaves differently for two identities is covered above")
    void theIdentityBearingSurfaceIsTheOneThisFileCovers() throws Exception {
        Map<String, Function<Cookie, RequestBuilder>> operations = everyOperation();

        // The detector can only look at operations it has been handed, so the list it is
        // handed has to be the whole documented surface. Without this an endpoint added to
        // the contract would be invisible here and the gate would pass by not looking.
        Set<String> documented = new TreeSet<>(
                ApiContract.documentedOperations(ApiContract.readArtifact()).keySet());
        documented.remove("GET /metrics");
        assertThat(new TreeSet<>(operations.keySet()))
                .as("docs/openapi.json describes an operation this test does not drive, so it"
                        + " cannot say whether that operation is identity-bearing")
                .isEqualTo(documented);

        Set<String> identityBearing = new TreeSet<>();

        for (Map.Entry<String, Function<Cookie, RequestBuilder>> entry : operations.entrySet()) {
            Cookie alice = upload("AAPL");
            Cookie bob = upload("MSFT");

            // (a) does the answer depend on which person is asking?
            if (!describe(entry.getValue().apply(alice))
                    .equals(describe(entry.getValue().apply(bob)))) {
                identityBearing.add(entry.getKey());
                continue;
            }
            // (b) does calling it as Alice change Alice's object? A read-only endpoint that
            // ignores the cookie fails both and is correctly left out; /delete and /resolve
            // answer identically for the two people and are only caught by this half.
            Cookie carol = upload("AAPL");
            String before = body(get("/breaks.csv").cookie(carol));
            mvc.perform(entry.getValue().apply(carol));
            if (!before.equals(body(get("/breaks.csv").cookie(carol)))) {
                identityBearing.add(entry.getKey());
            }
        }

        assertThat(identityBearing)
                .as("an operation became identity-bearing and this file does not exercise it."
                        + " Add it to entryPointsThatTakeAnObject and to the write corpus"
                        + " before changing this list")
                .containsExactly("GET /breaks", "GET /breaks.csv", "POST /delete", "POST /resolve");
    }

    // ---- fixtures ------------------------------------------------------------------------

    /**
     * Every single-character mutation of a cookie value, plus the shape changes.
     *
     * <p>Both halves on purpose. Mutating the signature tests the MAC; mutating the id tests
     * that a valid-looking id is worth nothing without one, which is the half that a naive
     * implementation gets wrong by checking the store before checking the signature.
     */
    private static List<String> mutationsOf(String cookieValue) {
        List<String> out = new ArrayList<>();
        Random random = new Random(SEED);
        for (int i = 0; i < cookieValue.length(); i++) {
            char original = cookieValue.charAt(i);
            char replacement = original == 'A' ? 'B' : 'A';
            out.add(cookieValue.substring(0, i) + replacement + cookieValue.substring(i + 1));
        }
        int dot = cookieValue.lastIndexOf('.');
        String id = cookieValue.substring(0, dot);
        String signature = cookieValue.substring(dot + 1);
        out.add(id);
        out.add(id + ".");
        out.add("." + signature);
        out.add(id + "." + signature + "=");
        out.add(id + "." + signature.substring(0, signature.length() - 1));
        out.add(id + "." + signature + signature);
        out.add(id + "." + id + "." + signature);
        out.add(id.toUpperCase(java.util.Locale.ROOT) + "." + signature);
        out.add(id + "." + signature.toLowerCase(java.util.Locale.ROOT));
        out.add(" " + cookieValue);
        out.add(cookieValue + " ");
        out.add("");
        out.add(".");
        for (int i = 0; i < 20; i++) {
            byte[] bytes = new byte[24];
            random.nextBytes(bytes);
            out.add(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                    + "." + signature);
        }
        return out;
    }

    /** The four requests that name an object, as a function of the identity presenting them. */
    private List<Function<Cookie, RequestBuilder>> entryPointsThatTakeAnObject() {
        return List.of(
                cookie -> withCookie(get("/breaks"), cookie),
                cookie -> withCookie(get("/breaks.csv"), cookie),
                cookie -> withCookie(post("/delete"), cookie),
                cookie -> withCookie(post("/resolve")
                        .param("kind", "split").param("symbol", "AAPL")
                        .param("detail", "4:1").param("on", "2020-08-31"), cookie));
    }

    /** Every documented operation, so the drift gate above cannot miss a new one. */
    private Map<String, Function<Cookie, RequestBuilder>> everyOperation() {
        Map<String, Function<Cookie, RequestBuilder>> operations = new LinkedHashMap<>();
        operations.put("GET /", cookie -> withCookie(get("/"), cookie));
        operations.put("GET /privacy", cookie -> withCookie(get("/privacy"), cookie));
        operations.put("GET /health", cookie -> withCookie(get("/health"), cookie));
        operations.put("GET /demo", cookie -> withCookie(get("/demo"), cookie));
        operations.put("GET /breaks", cookie -> withCookie(get("/breaks"), cookie));
        operations.put("GET /breaks.csv", cookie -> withCookie(get("/breaks.csv"), cookie));
        operations.put("POST /delete", cookie -> withCookie(post("/delete"), cookie));
        operations.put("POST /resolve", cookie -> withCookie(post("/resolve")
                .param("kind", "split").param("symbol", "AAPL")
                .param("detail", "4:1").param("on", "2020-08-31"), cookie));
        operations.put("POST /check", cookie -> cookie == null
                ? multipart("/check").file(history("AAPL")).file(positions("AAPL"))
                : multipart("/check").file(history("AAPL")).file(positions("AAPL")).cookie(cookie));
        // /metrics counts sessions held, so it moves for reasons that are nothing to do with
        // who is asking. It is left out of the detector deliberately and named here rather
        // than dropped silently; it reads no cookie, which SessionStore and the contract both
        // show, and it renders no upload.
        return operations;
    }

    private static MockHttpServletRequestBuilder withCookie(
            MockHttpServletRequestBuilder builder, Cookie cookie) {
        return cookie == null ? builder : builder.cookie(cookie);
    }

    /** Status, Location and body, which together are the whole of what a stranger can see. */
    private String describe(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        return result.getResponse().getStatus()
                + " | " + result.getResponse().getHeader("Location")
                + " | " + result.getResponse().getContentType()
                + " | " + result.getResponse().getContentAsString();
    }

    private String body(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    /**
     * One identity, with a position file, because an object with no rows in it cannot show
     * that it was left alone.
     *
     * <p>The broker is told there are 40 shares against the 10 the history buys, so the upload
     * carries a real break naming the symbol. Without the second file the export is a header
     * line and nothing else, and "unchanged" would be true of an empty object.
     */
    private Cookie upload(String symbol) throws Exception {
        return mvc.perform(multipart("/check").file(history(symbol)).file(positions(symbol)))
                .andReturn().getResponse().getCookie(SessionCookie.NAME);
    }

    private static MockMultipartFile positions(String symbol) {
        String csv = "symbol,quantity,cost_basis,kind\n" + symbol + ",40,,EQUITY\n";
        return new MockMultipartFile("positions", "positions.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
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
}
