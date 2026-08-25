package com.basis.reference;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the market data provider.
 *
 * @param key the API key. Provided by the environment, never committed, and never logged:
 *     the provider takes it as a query parameter, so anything that prints a URL prints the
 *     credential.
 * @param refreshAfter how old a successful fetch has to be before a symbol is worth
 *     spending a request on again
 * @param dailyRequestBudget the most requests one refresh run will make. Week 0 could not
 *     establish the free tier's published limit, and the provider returns no rate limit
 *     headers, so this is a deliberate ceiling rather than a derived one. See
 *     docs/FEASIBILITY.md.
 */
@ConfigurationProperties("basis.fmp")
public record FmpProperties(
        String baseUrl,
        String key,
        boolean enabled,
        Duration timeout,
        Duration refreshAfter,
        int dailyRequestBudget) {

    public FmpProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://financialmodelingprep.com/stable" : baseUrl;
        key = key == null ? "" : key.trim();
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        refreshAfter = refreshAfter == null ? Duration.ofDays(7) : refreshAfter;
        dailyRequestBudget = dailyRequestBudget <= 0 ? 125 : dailyRequestBudget;
    }

    public boolean hasKey() {
        return !key.isEmpty();
    }

    /**
     * @throws IllegalStateException when the fetcher is switched on without a key, which is
     *     better found at startup than after a refresh run has quietly recorded a
     *     UNAUTHORIZED failure against every symbol held
     */
    public void requireUsable() {
        if (enabled && !hasKey()) {
            throw new IllegalStateException("basis.fmp.enabled is true but basis.fmp.key is empty."
                    + " Set the FMP_KEY environment variable. Note that Spring does not read the .env"
                    + " file in this repository: export it first, for example with"
                    + " 'set -a; source .env; set +a'.");
        }
    }
}
