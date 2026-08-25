package com.basis.reference;

import com.basis.persistence.ReferenceDataRepository;
import com.basis.reconcile.ExchangeRates;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Exchange rates from the cache, and only from the cache.
 *
 * <p>Reconciliation never fetches. That is the same rule split history follows and it exists
 * for the same two reasons: a reconcile should be reproducible, and a reconcile should not
 * silently spend somebody's API quota or hang on a provider that is down. Rates are brought in
 * by {@code basis refresh-fx}, deliberately, and then reconciliation reads what is there.
 *
 * <p>An inverse pair is derived rather than fetched. Having USDEUR when EURUSD was asked for
 * is the same fact, and dividing one into the other is exact enough at the scale money is
 * compared to. Fetching both would double the quota cost of every currency for no new
 * information.
 */
@Component
public class CachedExchangeRates implements ExchangeRates {

    /**
     * Scale for a derived inverse. Six places, matching {@code Price}, which is more precision
     * than any cost basis comparison resolves to and keeps the arithmetic exact and bounded.
     */
    private static final int INVERSE_SCALE = 6;

    private final ReferenceDataRepository referenceData;

    public CachedExchangeRates(ReferenceDataRepository referenceData) {
        this.referenceData = referenceData;
    }

    @Override
    public Optional<Quote> rate(Currency from, Currency to, LocalDate on) {
        if (from.equals(to)) {
            return Optional.of(new Quote(java.math.BigDecimal.ONE, on, "identity"));
        }
        Optional<Quote> direct = referenceData.rateAt(pair(from, to), on);
        if (direct.isPresent()) {
            return direct;
        }
        return referenceData.rateAt(pair(to, from), on)
                .map(inverse -> new Quote(
                        java.math.BigDecimal.ONE.divide(
                                inverse.rate(), INVERSE_SCALE, java.math.RoundingMode.HALF_EVEN),
                        inverse.asOf(),
                        inverse.source() + " inverted"));
    }

    /** How this provider names a pair: the two codes run together, base first. */
    public static String pair(Currency from, Currency to) {
        return from.getCurrencyCode() + to.getCurrencyCode();
    }
}
