package com.basis.importer;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What Fidelity's action text means.
 *
 * <p>The most likely thing in this project to be wrong, and deliberately the easiest thing
 * to fix. Fidelity writes prose in the Action column, not codes, and the exact wording
 * varies by account type and by year. So the table is data, matched by prefix, and adding a
 * missing phrase is one line.
 *
 * <p>Prefix matching rather than equality, because the real text usually continues past the
 * verb: "YOU BOUGHT PROSHARES ULTRAPRO QQQ" and "DIVIDEND RECEIVED APPLE INC (AAPL)". The
 * longest matching prefix wins, so a specific phrase beats a general one that starts the
 * same way.
 *
 * <p>An action that matches nothing is not skipped. See
 * {@link StatementRowMapper} for why: a silently dropped row becomes a break weeks later
 * with a confidently wrong explanation attached, which is worse than an import that stops
 * and names the line it could not read.
 */
public final class FidelityActions {

    /** What a row does to the ledger, before the details are worked out. */
    public enum Kind {
        BUY,
        SELL,
        /** A dividend paid in cash. */
        CASH_DIVIDEND,
        /** A dividend immediately reinvested, which is a distribution followed by a purchase. */
        REINVESTMENT,
        /** A charge against the account: commission billed separately, ADR fee, service fee. */
        FEE,
        /** Tax withheld at source, arriving as its own line rather than netted into a dividend. */
        WITHHOLDING,
        /** Cash moving in or out of the account. */
        CASH_TRANSFER,
        /** Securities moving in or out of the account. */
        SECURITY_TRANSFER
    }

    private static final Map<String, Kind> BY_PREFIX = new LinkedHashMap<>();

    static {
        // Trades.
        BY_PREFIX.put("YOU BOUGHT", Kind.BUY);
        BY_PREFIX.put("BOUGHT", Kind.BUY);
        BY_PREFIX.put("PURCHASE", Kind.BUY);
        BY_PREFIX.put("YOU SOLD", Kind.SELL);
        BY_PREFIX.put("SOLD", Kind.SELL);
        BY_PREFIX.put("REDEMPTION", Kind.SELL);

        // Distributions. Reinvestment is listed before the plain dividend so the longer,
        // more specific phrase wins when both would match.
        BY_PREFIX.put("REINVESTMENT", Kind.REINVESTMENT);
        BY_PREFIX.put("DIVIDEND REINVEST", Kind.REINVESTMENT);
        BY_PREFIX.put("DIVIDEND RECEIVED", Kind.CASH_DIVIDEND);
        BY_PREFIX.put("CASH DIVIDEND", Kind.CASH_DIVIDEND);
        BY_PREFIX.put("LONG-TERM CAP GAIN", Kind.CASH_DIVIDEND);
        BY_PREFIX.put("SHORT-TERM CAP GAIN", Kind.CASH_DIVIDEND);
        BY_PREFIX.put("CAPITAL GAIN", Kind.CASH_DIVIDEND);

        // Charges.
        BY_PREFIX.put("FEE CHARGED", Kind.FEE);
        BY_PREFIX.put("SERVICE FEE", Kind.FEE);
        BY_PREFIX.put("ADR FEE", Kind.FEE);
        BY_PREFIX.put("FOREIGN TAX PAID", Kind.WITHHOLDING);
        BY_PREFIX.put("TAX WITHHELD", Kind.WITHHOLDING);
        BY_PREFIX.put("WITHHOLDING", Kind.WITHHOLDING);

        // Movement.
        BY_PREFIX.put("ELECTRONIC FUNDS TRANSFER", Kind.CASH_TRANSFER);
        BY_PREFIX.put("DIRECT DEPOSIT", Kind.CASH_TRANSFER);
        BY_PREFIX.put("DIRECT DEBIT", Kind.CASH_TRANSFER);
        BY_PREFIX.put("WIRE TRANSFER", Kind.CASH_TRANSFER);
        BY_PREFIX.put("CASH CONTRIBUTION", Kind.CASH_TRANSFER);
        BY_PREFIX.put("CASH DISTRIBUTION", Kind.CASH_TRANSFER);
        BY_PREFIX.put("JOURNALED", Kind.CASH_TRANSFER);
        BY_PREFIX.put("TRANSFERRED SECURITIES", Kind.SECURITY_TRANSFER);
        BY_PREFIX.put("TRANSFER OF ASSETS", Kind.SECURITY_TRANSFER);
    }

    private FidelityActions() {
    }

    /**
     * The longest matching prefix, so a specific phrase beats a general one.
     *
     * @return empty when nothing matches, which the caller must treat as an error rather
     *     than as a row worth ignoring
     */
    public static Optional<Kind> classify(String action) {
        String text = action.toUpperCase(Locale.ROOT).trim();
        String bestPrefix = null;
        Kind best = null;
        for (Map.Entry<String, Kind> candidate : BY_PREFIX.entrySet()) {
            String prefix = candidate.getKey();
            if (text.startsWith(prefix) && (bestPrefix == null || prefix.length() > bestPrefix.length())) {
                bestPrefix = prefix;
                best = candidate.getValue();
            }
        }
        return Optional.ofNullable(best);
    }

    /** Every phrase understood, for an error message that tells someone what to compare against. */
    public static java.util.Set<String> known() {
        return BY_PREFIX.keySet();
    }
}
