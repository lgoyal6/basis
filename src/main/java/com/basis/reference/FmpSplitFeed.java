package com.basis.reference;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reads split history from Financial Modeling Prep.
 *
 * <p>Uses the JDK's own HTTP client and adds no dependency. Spring's {@code RestClient}
 * would mean putting a web stack on the classpath of an application that deliberately runs
 * with no web layer at all, to make one GET request.
 *
 * <p>No JSON parser either. The body is handed to Postgres, which already parses it in
 * {@code ReferenceDataRepository.ingestSplits}, so the only structural question asked here
 * is whether the response is the array it should be.
 *
 * <p>The failure shapes below were measured against the live provider rather than guessed,
 * and two of them are surprising:
 *
 * <ul>
 *   <li>402 bodies are <b>plain text</b>, not JSON. A parser pointed at them throws.
 *   <li>A symbol outside the subscription returns <b>402, not an empty array</b>. So on the
 *       free tier "cannot check" is the common outcome, which is exactly why the caller
 *       records the fetch and not just its results.
 *   <li>401 does return JSON, as {@code {"Error Message": ...}}.
 * </ul>
 */
@Component
public class FmpSplitFeed implements SplitFeed {

    private static final Logger log = LoggerFactory.getLogger(FmpSplitFeed.class);

    /** Enough of a failure body to diagnose it, short enough not to fill a log line. */
    private static final int DETAIL_LIMIT = 200;

    private final FmpProperties properties;
    private final HttpClient http;

    // Two constructors, so the container needs telling which one to use.
    @Autowired
    public FmpSplitFeed(FmpProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
    }

    /** Visible for tests, which supply a client that never opens a socket. */
    FmpSplitFeed(FmpProperties properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    @Override
    public FeedResult fetchSplits(String symbol) {
        if (!properties.hasKey()) {
            return FeedResult.failed(FeedOutcome.UNAUTHORIZED, 0,
                    "no API key configured; set FMP_KEY in the environment");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(splitsUri(symbol))
                .timeout(properties.timeout())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Deliberately not logging the request: the key rides in the query string.
            log.warn("split fetch for {} failed in transport: {}", symbol, e.toString());
            return FeedResult.failed(FeedOutcome.TRANSPORT_ERROR, 0, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FeedResult.failed(FeedOutcome.TRANSPORT_ERROR, 0, "interrupted");
        }

        return interpret(symbol, response.statusCode(), response.body());
    }

    /** Split out so the response shapes can be tested without a socket. */
    static FeedResult interpret(String symbol, int status, String rawBody) {
        String body = rawBody == null ? "" : rawBody.trim();

        if (status == 401) {
            return FeedResult.failed(FeedOutcome.UNAUTHORIZED, status, truncate(body));
        }
        if (status == 402) {
            return FeedResult.failed(FeedOutcome.NOT_AVAILABLE, status, truncate(body));
        }
        if (status < 200 || status >= 300) {
            return FeedResult.failed(FeedOutcome.UNEXPECTED, status, truncate(body));
        }
        // A successful splits response is a JSON array. Anything else at 200, including the
        // provider's {"Error Message": ...} object, is a failure wearing a success status.
        if (!body.startsWith("[")) {
            return FeedResult.failed(FeedOutcome.UNEXPECTED, status,
                    "expected a JSON array for " + symbol + " but got " + truncate(body));
        }
        return FeedResult.ok(body);
    }

    private URI splitsUri(String symbol) {
        return URI.create(properties.baseUrl() + "/splits"
                + "?symbol=" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "&apikey=" + URLEncoder.encode(properties.key(), StandardCharsets.UTF_8));
    }

    private static String truncate(String body) {
        String collapsed = body.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= DETAIL_LIMIT ? collapsed : collapsed.substring(0, DETAIL_LIMIT) + "...";
    }
}
