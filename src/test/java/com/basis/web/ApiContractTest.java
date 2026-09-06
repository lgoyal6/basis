package com.basis.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The checked contract and the running app, compared in both directions.
 *
 * <p>A contract test that only checks one direction is half a test, and it is always the same
 * half that gets written. Documenting an endpoint that no longer exists is caught by looking
 * from the document at the app. An endpoint added and never documented is only caught by
 * looking from the app at the document, and that is the direction a stale file survives.
 *
 * <p>Both are here, plus the freshness check that stops the file drifting from the annotations
 * it was generated from, plus a check that the request fields the document advertises are the
 * ones the handler signatures actually read.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("web")
@org.testcontainers.junit.jupiter.Testcontainers
// Ordered only so that the regeneration run works from a standing start: with
// -Dbasis.openapi.write=true the first test writes the artifact the other five read, and
// without an order JUnit is free to read it before it exists.
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class ApiContractTest {

    // Before the context exists, because springdoc's registry is consulted when the document
    // is first built and a document built without it silently covers two endpoints of ten.
    static {
        ApiContract.registerViewControllers();
    }

    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    @org.testcontainers.junit.jupiter.Container
    static org.testcontainers.containers.PostgreSQLContainer<?> postgres =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RequestMappingHandlerMapping mapping;

    private String generated() throws Exception {
        String raw = mvc.perform(get("/v3/api-docs"))
                .andReturn().getResponse().getContentAsString();
        return ApiContract.canonicalise(raw);
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("the checked contract is the document this build generates, so it cannot go stale")
    void artifactIsFresh() throws Exception {
        String generated = generated();
        if (Boolean.getBoolean("basis.openapi.write")) {
            Files.createDirectories(ApiContract.ARTIFACT.getParent());
            Files.writeString(ApiContract.ARTIFACT, generated);
            return;
        }
        assertThat(ApiContract.ARTIFACT)
                .as("docs/openapi.json is missing. Generate it with:"
                        + " ./gradlew test --tests '*ApiContractTest' -Dbasis.openapi.write=true")
                .exists();
        assertThat(Files.readString(ApiContract.ARTIFACT))
                .as("the checked contract no longer matches what the handlers generate."
                        + " If the change is intended, regenerate with:"
                        + " ./gradlew test --tests '*ApiContractTest' -Dbasis.openapi.write=true")
                .isEqualTo(generated);
    }

    @Test
    @DisplayName("every documented operation is one the app actually serves")
    void contractDescribesNothingImaginary() throws Exception {
        Set<String> documented = ApiContract.documentedOperations(ApiContract.readArtifact())
                .keySet();
        Set<String> live = ApiContract.liveOperations(mapping).keySet();

        assertThat(documented)
                .as("these are in docs/openapi.json and no handler answers them, so a caller"
                        + " reading the contract would be sent somewhere that 404s")
                .isSubsetOf(live);
        assertThat(documented).isNotEmpty();
    }

    @Test
    @DisplayName("every endpoint the app serves is in the contract, which is the direction a stale file survives")
    void contractDescribesEverythingReal() throws Exception {
        Set<String> documented = ApiContract.documentedOperations(ApiContract.readArtifact())
                .keySet();
        Map<String, HandlerMethod> live = ApiContract.liveOperations(mapping);

        assertThat(live.keySet())
                .as("these are served by basis and are not in docs/openapi.json. Add them to"
                        + " ApiContract.DECLARED_RESPONSES with the statuses and media types you"
                        + " observed, then regenerate the artifact")
                .isSubsetOf(documented);

        // Not an accident of counting: this is the whole application surface, named. If a
        // handler is deleted the previous assertion stays green because a subset shrinks, so
        // the count is what notices a removal that nobody updated the contract for.
        assertThat(live.keySet()).hasSize(10);
    }

    @Test
    @DisplayName("the request fields the contract advertises are the ones the handlers read")
    void requestFieldsAgreeWithTheHandlerSignatures() throws Exception {
        Map<String, ObjectNode> documented =
                ApiContract.documentedOperations(ApiContract.readArtifact());
        Map<String, HandlerMethod> live = ApiContract.liveOperations(mapping);

        Map<String, String> mismatches = new TreeMap<>();
        documented.forEach((operation, node) -> {
            HandlerMethod handler = live.get(operation);
            if (handler == null) {
                return;
            }
            List<ApiContract.Input> fromDocument = ApiContract.inputsFromDocument(node);
            List<ApiContract.Input> fromHandler = ApiContract.inputsFromHandler(handler);
            if (!fromDocument.equals(fromHandler)) {
                mismatches.put(operation,
                        "contract says " + fromDocument + ", handler reads " + fromHandler);
            }
        });

        assertThat(mismatches)
                .as("the document and the handler signature disagree about what a caller sends."
                        + " Reflection over the method is the source of truth here; the document"
                        + " is what has to move")
                .isEmpty();

        // The two operations that take anything at all, spelled out, so this test cannot pass
        // by comparing two empty lists after somebody deletes the parameters.
        assertThat(ApiContract.inputsFromDocument(documented.get("POST /check")))
                .containsExactly(
                        new ApiContract.Input("broker", false),
                        new ApiContract.Input("history", true),
                        new ApiContract.Input("positions", false));
        assertThat(ApiContract.inputsFromDocument(documented.get("POST /resolve")))
                .containsExactly(
                        new ApiContract.Input("detail", true),
                        new ApiContract.Input("kind", true),
                        new ApiContract.Input("on", true),
                        new ApiContract.Input("symbol", true));
    }

    @Test
    @DisplayName("where a handler pins the media type it produces, the contract pins the same one")
    void producesConditionsAgreeWithTheContract() throws Exception {
        Map<String, ObjectNode> documented =
                ApiContract.documentedOperations(ApiContract.readArtifact());

        Map<String, Set<String>> pinned = new TreeMap<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            Set<org.springframework.http.MediaType> produces =
                    info.getProducesCondition().getProducibleMediaTypes();
            if (produces.isEmpty() || !handler.getBeanType().getPackageName().startsWith("com.basis")) {
                return;
            }
            for (String path : pathsOf(info)) {
                info.getMethodsCondition().getMethods().forEach(method -> pinned.put(
                        method.name() + " " + path,
                        produces.stream().map(org.springframework.http.MediaType::toString)
                                .collect(java.util.stream.Collectors
                                        .toCollection(java.util.TreeSet::new))));
            }
        });

        assertThat(pinned.keySet())
                .as("if a handler stops pinning a media type, or a new one starts, this test"
                        + " should be told rather than quietly checking nothing")
                .containsExactly("GET /breaks.csv", "GET /health");

        pinned.forEach((operation, mediaTypes) -> {
            Set<String> inContract = new java.util.TreeSet<>();
            documented.get(operation).path("responses").path("200").path("content")
                    .fieldNames().forEachRemaining(inContract::add);
            assertThat(inContract)
                    .as(operation + " is pinned to " + mediaTypes + " by its mapping")
                    .isEqualTo(mediaTypes);
        });
    }

    private static Set<String> pathsOf(RequestMappingInfo info) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (info.getPathPatternsCondition() != null) {
            info.getPathPatternsCondition().getPatterns()
                    .forEach(pattern -> out.add(pattern.getPatternString()));
            return out;
        }
        return info.getPatternValues();
    }

    @Test
    @DisplayName("the contract names the app and a version, because a document nobody can identify is not one")
    void theDocumentIdentifiesItself() throws Exception {
        JsonNode info = ApiContract.readArtifact().get("info");
        assertThat(info.get("title").asText()).isEqualTo(ApiContract.TITLE);
        assertThat(info.get("version").asText()).isEqualTo(ApiContract.VERSION);
        assertThat(ApiContract.readArtifact().has("servers"))
                .as("a server URL in the artifact makes it differ per environment, and then the"
                        + " freshness check fails for a reason that is not drift")
                .isFalse();
    }
}
