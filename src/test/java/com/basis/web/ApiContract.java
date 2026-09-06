package com.basis.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The one place that knows what basis promises over HTTP, and the machinery for proving it.
 *
 * <p>The document at {@code docs/openapi.json} is a checked artifact rather than something a
 * running service serves. springdoc is a test dependency for exactly that reason: this app is
 * public and has no accounts, so publishing {@code /v3/api-docs} on the deploy would add an
 * unauthenticated surface and change nothing about whether the contract is true.
 *
 * <p>Two halves, and the split matters for what the tests can honestly claim.
 *
 * <p><b>springdoc derives the request half.</b> Paths, methods, parameters and their required
 * flags come from its annotation scan, which is a derivation independent of the one
 * {@link ApiContractTest} checks it against ({@link RequestMappingHandlerMapping} and
 * reflection over the handler signatures). Two independent readings agreeing is worth
 * something; a document generated from the mapping and then compared to the mapping is not.
 *
 * <p><b>This class declares the response half,</b> because springdoc cannot infer it. Every
 * page handler here returns a Thymeleaf view name, so springdoc reports "200, string" for a
 * handler that actually answers 302 with a Location. The declarations below are not guesses:
 * every status and media type in {@link #DECLARED_RESPONSES} was observed first, and
 * {@link ApiBoundaryTest} drives generated inputs at the app and fails if a response appears
 * that this file does not declare. So the hand-written half is the half under test, not the
 * half doing the testing.
 *
 * <p>springdoc does not see a {@code @Controller} at all unless the handler carries
 * {@code @ResponseBody}. Left alone it documents two of this app's ten endpoints.
 * {@link #registerViewControllers()} is the supported way to tell it otherwise.
 */
final class ApiContract {

    /** The checked artifact. Regenerate with -Dbasis.openapi.write=true, never by hand. */
    static final Path ARTIFACT = Path.of("docs", "openapi.json");

    static final String TITLE = "basis";
    static final String VERSION = "0.1.0";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Paths that belong to the framework or to springdoc itself, not to basis. */
    private static final Set<String> NOT_OURS = Set.of("/error", "/v3/api-docs", "/v3/api-docs.yaml");

    private ApiContract() {
    }

    /**
     * Makes springdoc look at the view-returning controllers.
     *
     * <p>Without this the generated document holds {@code GET /health} and
     * {@code GET /breaks.csv} and nothing else, because springdoc's {@code isRestController}
     * gate wants {@code @ResponseBody} or {@code @Operation} on the handler and those two are
     * the only ones that have it. A contract covering two endpoints out of ten, silently, is
     * worse than no contract: it passes.
     *
     * <p>Idempotent, and safe to call from a static initialiser: the registry is a static set
     * inside springdoc and the document is not built until something asks for it.
     */
    static void registerViewControllers() {
        org.springdoc.core.utils.SpringDocUtils.getConfig()
                .addRestControllers(UploadController.class, MetricsController.class);
    }

    /**
     * The responses basis actually gives, per operation.
     *
     * <p>Keyed "METHOD /path". Each entry is a status mapped to the media type of the body, or
     * to {@code null} where there is no body. Redirects carry a Location and no content type,
     * which is why 302 maps to null rather than to text/html.
     */
    static final Map<String, Map<Integer, String>> DECLARED_RESPONSES = declaredResponses();

    private static Map<String, Map<Integer, String>> declaredResponses() {
        Map<String, Map<Integer, String>> byOperation = new TreeMap<>();
        byOperation.put("GET /", mapOf(200, "text/html"));
        byOperation.put("GET /privacy", mapOf(200, "text/html"));
        byOperation.put("GET /metrics", mapOf(200, "text/html"));
        byOperation.put("GET /health", mapOf(200, "text/plain", 406, null, 503, "text/plain"));
        byOperation.put("GET /demo", mapOf(302, null));
        byOperation.put("GET /breaks", (mapOf(200, "text/html", 302, null)));
        byOperation.put("GET /breaks.csv",
                (mapOf(200, "text/csv", 404, "text/csv", 406, null)));
        // 200 is gone from both, and its absence is the fix rather than an omission. Every
        // 200 these two ever produced came from an exception handler rendering the form
        // again, so a refused upload and a computed answer were the same status line;
        // Schemathesis posted no body to /check, a request the contract requires one for,
        // and got 200 OK. The refusals are 400 now and carry the page they always carried,
        // which is also what stopped the framework's application/json error body escaping
        // from a service that declares no JSON anywhere.
        byOperation.put("POST /check",
                (mapOf(302, null, 400, "text/html", 500, "text/html")));
        byOperation.put("POST /resolve",
                (mapOf(302, null, 400, "text/html", 500, "text/html")));
        byOperation.put("POST /delete", mapOf(302, null));
        return byOperation;
    }

    /** What each status means, so the document reads as a description rather than a table. */
    private static final Map<String, Map<Integer, String>> DESCRIPTIONS = descriptions();

    private static Map<String, Map<Integer, String>> descriptions() {
        Map<String, Map<Integer, String>> d = new TreeMap<>();
        d.put("GET /", mapOf(200, "The upload form."));
        d.put("GET /privacy", mapOf(200,
                "The retention promise, rendering the lifetime this deploy is configured with."));
        d.put("GET /metrics", mapOf(200, "Counters, with real and demo kept apart."));
        d.put("GET /health", mapOf(200, "The database answered.",
                406, "The caller asked for a media type this endpoint does not produce. It is"
                        + " pinned to text/plain so that a request for JSON is refused rather"
                        + " than answered with prose labelled as JSON.",
                503, "The database did not answer. The reason is withheld on purpose: this"
                        + " endpoint is unauthenticated and a driver message names hosts,"
                        + " users and schemas."));
        d.put("GET /demo", mapOf(302, "A seeded session was created. Location is /breaks."));
        d.put("GET /breaks", mapOf(200, "The break list for the session named by the cookie.",
                302, "No live session. Location is /?expired=1, and it is the same answer for"
                        + " a missing cookie, an unparseable one, and a valid one whose upload"
                        + " has been deleted or has expired."));
        d.put("GET /breaks.csv", mapOf(200, "The break list as CSV.",
                404, "No live session. The body is the fixed string \"no session\"; it does not"
                        + " say whether the id existed.",
                406, "The client asked for a media type this endpoint does not produce."));
        d.put("POST /check", mapOf(
                302, "The statement parsed. A session cookie is set and Location is /breaks.",
                400, "The upload was refused, and the form is rendered again with the problem"
                        + " and the next step. Oversized files, files that are not CSV, a"
                        + " broker that is not in the profile list, a position file in the"
                        + " history slot, a multipart request with no part named history, a"
                        + " body that stops mid-stream and a body that is not multipart at all"
                        + " land here.",
                500, "basis failed unexpectedly. The form is rendered again with the message;"
                        + " nothing was stored."));
        d.put("POST /resolve", mapOf(
                302, "The decision was recorded, declined or refused. Location is /breaks,"
                        + " /breaks#kept, /breaks?refused=1, or /?expired=1 when there is no"
                        + " live session.",
                400, "A required field was absent, or the decision could not be read at all,"
                        + " for example an unparseable date. The form is rendered again with"
                        + " the problem.",
                500, "basis failed unexpectedly. The form is rendered again with the message;"
                        + " nothing was stored."));
        d.put("POST /delete", mapOf(302,
                "The session is gone if there was one. Location is /?deleted=1, and the answer"
                        + " is identical when no cookie was sent."));
        return d;
    }

    /**
     * Turns springdoc's document into the checked artifact.
     *
     * <p>springdoc keeps paths, methods, parameters and request bodies. Everything else is
     * replaced, and each replacement has a reason:
     *
     * <ul>
     *   <li>{@code servers} is dropped. It is "http://localhost" under MockMvc and whatever the
     *       deploy is otherwise, so leaving it in makes the artifact differ by environment and
     *       the drift test fail for a reason that is not drift.
     *   <li>{@code info} is pinned, because "OpenAPI definition v0" names nothing.
     *   <li>{@code tags} are dropped. They are derived from class names and change when a class
     *       is renamed without the contract changing.
     *   <li>{@code responses} are replaced with the observed ones. See the class comment.
     *   <li>POST /check's request body is replaced. springdoc types it application/json, which
     *       is wrong for an endpoint that takes only multipart, and it puts broker in the query
     *       string while the two file parts go in the body. The replacement puts all three
     *       where they are actually sent, and ApiContractTest checks the result against the
     *       handler's own parameter list by reflection, so it cannot quietly stop matching.
     * </ul>
     */
    static String canonicalise(String springdocJson) throws IOException {
        ObjectNode doc = (ObjectNode) JSON.readTree(springdocJson);
        doc.remove("servers");
        doc.remove("tags");
        ObjectNode info = JSON.createObjectNode();
        info.put("title", TITLE);
        info.put("version", VERSION);
        info.put("description",
                "Checked contract for the basis web layer. Generated from the handler"
                        + " annotations by springdoc, with the response half declared in"
                        + " ApiContract and proved against real responses by ApiBoundaryTest."
                        + " Regenerate with: ./gradlew test --tests '*ApiContractTest'"
                        + " -Dbasis.openapi.write=true");
        doc.set("info", info);

        ObjectNode paths = (ObjectNode) doc.get("paths");
        paths.fields().forEachRemaining(pathEntry -> {
            String path = pathEntry.getKey();
            ObjectNode operations = (ObjectNode) pathEntry.getValue();
            operations.fields().forEachRemaining(opEntry -> {
                String key = opEntry.getKey().toUpperCase(Locale.ROOT) + " " + path;
                ObjectNode operation = (ObjectNode) opEntry.getValue();
                operation.remove("tags");
                if ("POST /check".equals(key)) {
                    operation.remove("parameters");
                    operation.set("requestBody", uploadRequestBody());
                }
                operation.set("responses", responsesFor(key));
            });
        });
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sorted(doc)) + "\n";
    }

    private static ObjectNode uploadRequestBody() {
        ObjectNode history = JSON.createObjectNode();
        history.put("type", "string");
        history.put("format", "binary");
        // minLength, because the app refuses an empty file and the document did not say so:
        // Schemathesis generated a zero-byte history part, which the schema permitted, and
        // reported basis as rejecting a schema-compliant request. The constraint is real
        // either way - a file with no bytes has no transactions in it - so the honest fix is
        // to publish it rather than to accept the file.
        history.put("minLength", 1);
        history.put("description", "The transaction history, as CSV exported by the broker."
                + " At least one byte: an empty file is refused.");
        ObjectNode positions = JSON.createObjectNode();
        positions.put("type", "string");
        positions.put("format", "binary");
        positions.put("description",
                "The position statement, optional. Without it every break is a gap against"
                        + " nothing rather than against what the broker says you hold.");
        ObjectNode broker = JSON.createObjectNode();
        broker.put("type", "string");
        broker.put("description",
                "One of the names in config/brokers, or absent to detect it from the header"
                        + " row. Anything else is refused: this value goes on to name a file.");

        ObjectNode properties = JSON.createObjectNode();
        properties.set("history", history);
        properties.set("positions", positions);
        properties.set("broker", broker);

        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode required = schema.putArray("required");
        required.add("history");

        ObjectNode media = JSON.createObjectNode();
        media.set("schema", schema);
        ObjectNode content = JSON.createObjectNode();
        content.set("multipart/form-data", media);
        ObjectNode body = JSON.createObjectNode();
        body.put("required", true);
        body.set("content", content);
        return body;
    }

    private static ObjectNode responsesFor(String operationKey) {
        Map<Integer, String> declared = DECLARED_RESPONSES.get(operationKey);
        if (declared == null) {
            throw new IllegalStateException(
                    "the app answers " + operationKey + " and this contract does not declare it."
                            + " Add it to ApiContract.DECLARED_RESPONSES with the status codes and"
                            + " media types you observed, then regenerate.");
        }
        Map<Integer, String> texts = DESCRIPTIONS.getOrDefault(operationKey, Map.of());
        ObjectNode responses = JSON.createObjectNode();
        new TreeMap<>(declared).forEach((status, mediaType) -> {
            ObjectNode response = JSON.createObjectNode();
            response.put("description", texts.getOrDefault(status, "See the handler."));
            if (mediaType != null) {
                ObjectNode schema = JSON.createObjectNode();
                schema.put("type", "string");
                ObjectNode media = JSON.createObjectNode();
                media.set("schema", schema);
                ObjectNode content = JSON.createObjectNode();
                content.set(mediaType, media);
                response.set("content", content);
            }
            if (status == 302) {
                ObjectNode location = JSON.createObjectNode();
                location.put("description", "Where the browser goes next.");
                ObjectNode locationSchema = JSON.createObjectNode();
                locationSchema.put("type", "string");
                location.set("schema", locationSchema);
                ObjectNode headers = JSON.createObjectNode();
                headers.set("Location", location);
                response.set("headers", headers);
            }
            responses.set(String.valueOf(status), response);
        });
        return responses;
    }

    /** Recursively key-sorted, so the artifact's diff is the contract's diff and nothing else. */
    private static JsonNode sorted(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Map<String, JsonNode> fields = new TreeMap<>();
            object.fields().forEachRemaining(e -> fields.put(e.getKey(), sorted(e.getValue())));
            ObjectNode out = JSON.createObjectNode();
            fields.forEach(out::set);
            return out;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode out = JSON.createArrayNode();
            array.forEach(element -> out.add(sorted(element)));
            return out;
        }
        return node;
    }

    // ---- the live side, read from Spring rather than from the document --------------------

    /** Every operation basis itself serves, as "METHOD /path". */
    static Map<String, HandlerMethod> liveOperations(RequestMappingHandlerMapping mapping) {
        Map<String, HandlerMethod> live = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : mapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            for (String path : paths(info)) {
                if (NOT_OURS.contains(path)) {
                    continue;
                }
                Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                        info.getMethodsCondition().getMethods();
                if (methods.isEmpty()) {
                    live.put("* " + path, entry.getValue());
                }
                for (var method : methods) {
                    live.put(method.name() + " " + path, entry.getValue());
                }
            }
        }
        return live;
    }

    private static Set<String> paths(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            Set<String> out = new java.util.LinkedHashSet<>();
            info.getPathPatternsCondition().getPatterns()
                    .forEach(pattern -> out.add(pattern.getPatternString()));
            return out;
        }
        return info.getPatternValues();
    }

    /** Every documented operation, as "METHOD /path". */
    static Map<String, ObjectNode> documentedOperations(JsonNode document) {
        Map<String, ObjectNode> out = new TreeMap<>();
        JsonNode paths = document.get("paths");
        paths.fields().forEachRemaining(pathEntry -> pathEntry.getValue().fields()
                .forEachRemaining(opEntry -> out.put(
                        opEntry.getKey().toUpperCase(Locale.ROOT) + " " + pathEntry.getKey(),
                        (ObjectNode) opEntry.getValue())));
        return out;
    }

    /** One request input: the name a caller sends and whether it may be left out. */
    record Input(String name, boolean required) implements Comparable<Input> {
        @Override
        public int compareTo(Input other) {
            return name.compareTo(other.name);
        }

        @Override
        public String toString() {
            return name + (required ? " (required)" : " (optional)");
        }
    }

    /** What the handler's own signature says it reads, by reflection. Nothing to do with the document. */
    static List<Input> inputsFromHandler(HandlerMethod handler) {
        List<Input> inputs = new ArrayList<>();
        for (java.lang.reflect.Parameter parameter : handler.getMethod().getParameters()) {
            RequestParam annotation = parameter.getAnnotation(RequestParam.class);
            if (annotation == null) {
                continue;
            }
            String name = !annotation.value().isEmpty() ? annotation.value()
                    : !annotation.name().isEmpty() ? annotation.name() : parameter.getName();
            inputs.add(new Input(name, annotation.required()));
        }
        java.util.Collections.sort(inputs);
        return inputs;
    }

    /** What the document says a caller sends: query parameters plus multipart body fields. */
    static List<Input> inputsFromDocument(ObjectNode operation) {
        Map<String, Boolean> inputs = new LinkedHashMap<>();
        JsonNode parameters = operation.get("parameters");
        if (parameters != null) {
            parameters.forEach(parameter -> inputs.put(parameter.get("name").asText(),
                    parameter.path("required").asBoolean(false)));
        }
        JsonNode multipart = operation.path("requestBody").path("content")
                .path("multipart/form-data").path("schema");
        if (!multipart.isMissingNode()) {
            Set<String> required = new java.util.HashSet<>();
            multipart.path("required").forEach(name -> required.add(name.asText()));
            multipart.path("properties").fieldNames()
                    .forEachRemaining(name -> inputs.put(name, required.contains(name)));
        }
        List<Input> out = new ArrayList<>();
        inputs.forEach((name, required) -> out.add(new Input(name, required)));
        java.util.Collections.sort(out);
        return out;
    }

    static JsonNode readArtifact() throws IOException {
        return JSON.readTree(Files.readString(ARTIFACT));
    }

    static ObjectMapper json() {
        return JSON;
    }

    /** Map.of refuses a null value, and "302, no body" is exactly a null value. */
    private static Map<Integer, String> mapOf(Object... pairs) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((Integer) pairs[i], (String) pairs[i + 1]);
        }
        return out;
    }

}
