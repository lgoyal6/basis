package com.basis.reference;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Daily exchange rates, from the same provider as split history and on the same terms.
 *
 * <p>Fetched as a date range rather than a single day, because currency markets close. Asking
 * for a Sunday returns nothing at all, and a reconciliation dated on a weekend is completely
 * ordinary, so the request reaches back a few days and the caller takes the most recent
 * quote at or before the date it wanted. The date that rate is really from travels with it.
 *
 * <p>The pair is a symbol here, not two parameters: this provider quotes {@code EURUSD} the
 * way it quotes a ticker. That is a detail of one provider and the reason this class exists
 * separately from the {@code ExchangeRates} interface the reconciler depends on.
 */
@Component
public class FmpFxFeed {

    private static final Logger log = LoggerFactory.getLogger(FmpFxFeed.class);

    private static final int DETAIL_LIMIT = 200;

    /**
     * How far back to look for the most recent quote.
     *
     * <p>Long enough to clear a weekend plus a run of public holidays, short enough that a
     * rate is never silently weeks old. A pair with no quote in eleven days is a pair this
     * provider does not really cover, and that should surface as a refusal rather than as a
     * stale number.
     */
    private static final int LOOKBACK_DAYS = 11;

    private final FmpProperties properties;
    private final HttpClient http;

    // Two constructors, so the injectable one has to be named. Without this Spring finds no
    // default constructor and the whole context fails, which is how the split feed broke too.
    @org.springframework.beans.factory.annotation.Autowired
    public FmpFxFeed(FmpProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
    }

    FmpFxFeed(FmpProperties properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    /** The provider's response for one pair, or a reason there is not one. */
    public FeedResult fetchRates(String pair, LocalDate on) {
        if (!properties.hasKey()) {
            return FeedResult.failed(FeedOutcome.UNAUTHORIZED, 0,
                    "no API key configured; set FMP_KEY in the environment");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(ratesUri(pair, on))
                .timeout(properties.timeout())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // The pair and not the request: the key rides in the query string.
            log.warn("fx fetch for {} failed in transport: {}", pair, e.toString());
            return FeedResult.failed(FeedOutcome.TRANSPORT_ERROR, 0, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FeedResult.failed(FeedOutcome.TRANSPORT_ERROR, 0, "interrupted");
        }
        return interpret(pair, response.statusCode(), response.body());
    }

    /** Split out so the response shapes can be tested without a socket. */
    static FeedResult interpret(String pair, int status, String rawBody) {
        String body = rawBody == null ? "" : rawBody.trim();

        if (status == 401) {
            return FeedResult.failed(FeedOutcome.UNAUTHORIZED, status, truncate(body));
        }
        if (status == 402 || status == 403) {
            return FeedResult.failed(FeedOutcome.NOT_AVAILABLE, status, truncate(body));
        }
        if (status < 200 || status >= 300) {
            return FeedResult.failed(FeedOutcome.UNEXPECTED, status, truncate(body));
        }
        if (!body.startsWith("[")) {
            return FeedResult.failed(FeedOutcome.UNEXPECTED, status,
                    "expected a JSON array for " + pair + " but got " + truncate(body));
        }
        // An empty array is a real answer: this provider has no quote for that pair and range.
        // Reported as NOT_AVAILABLE rather than OK so a caller cannot mistake it for a rate.
        if (body.equals("[]")) {
            return FeedResult.failed(FeedOutcome.NOT_AVAILABLE, status,
                    "no quotes for " + pair + " in the requested range");
        }
        return FeedResult.ok(body);
    }

    private URI ratesUri(String pair, LocalDate on) {
        return URI.create(properties.baseUrl() + "/historical-price-eod/full"
                + "?symbol=" + URLEncoder.encode(pair, StandardCharsets.UTF_8)
                + "&from=" + on.minusDays(LOOKBACK_DAYS)
                + "&to=" + on
                + "&apikey=" + URLEncoder.encode(properties.key(), StandardCharsets.UTF_8));
    }

    private static String truncate(String body) {
        String collapsed = body.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= DETAIL_LIMIT ? collapsed : collapsed.substring(0, DETAIL_LIMIT) + "...";
    }
}
