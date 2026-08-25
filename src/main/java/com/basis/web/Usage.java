package com.basis.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Counters, and nothing that could identify anybody.
 *
 * <p>The point of the project is "N real errors found across N real accounts", which cannot be
 * claimed without counting. The temptation, once you are counting, is to keep the interesting
 * details: which symbols, what quantities, how big the account was. All of that would be a
 * record of somebody's portfolio held indefinitely, on a page that promises the opposite.
 *
 * <p>So what is recorded is a count per category. A break was found, of this kind. An upload
 * failed, for this reason. No symbols, no quantities, no dates, no filenames, no addresses,
 * nothing joinable back to a person or a holding. The numbers still answer the question
 * because the question is how many, not whose.
 *
 * <p>Demo sessions are counted separately throughout. A demo is seeded data proving the
 * classifier works, and folding it into the real total would turn the one metric that matters
 * into a number that flatters itself.
 */
@Component
// Web only. Scanned into a CLI context these would demand beans the web profile
// provides, which is how adding the web layer broke every Spring test at once.
@org.springframework.context.annotation.Profile("web")
public class Usage {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** An upload that parsed, with what it found. */
    public void parsed(String broker, BreakFinder.Result result, boolean demo) {
        String scope = scope(demo);
        bump(scope + ".uploads.parsed");
        bump(scope + ".uploads.parsed.broker." + broker);
        if (result.reconciled()) {
            bump(scope + ".reconciles");
            if (result.isClean()) {
                // Agreeing on the first pass is a real outcome and worth knowing the rate of:
                // a tool that only ever finds problems is indistinguishable from a tool that
                // invents them.
                bump(scope + ".reconciles.clean-first-pass");
            }
        }
        for (com.basis.reconcile.BreakRecord record : result.breaks()) {
            bump(scope + ".breaks." + record.cause().code());
            bump(scope + ".breaks." + (record.cause().confident() ? "confirmed" : "suspected"));
        }
        for (int i = 0; i < result.ambiguities().size(); i++) {
            bump(scope + ".ambiguities.surfaced");
        }
    }

    /** An upload that did not parse, categorised by why, which is also the bug backlog. */
    public void parseFailed(String broker, String reason, boolean demo) {
        bump(scope(demo) + ".uploads.failed");
        bump(scope(demo) + ".uploads.failed.reason." + slug(reason));
    }

    public void viewed(boolean demo) {
        bump(scope(demo) + ".breaks.viewed");
    }

    public void choiceApplied(String kind, boolean demo) {
        bump(scope(demo) + ".ambiguities.resolved." + slug(kind));
    }

    /** They were asked and said it was something else. Also an answer, and worth counting. */
    public void choiceDeclined(boolean demo) {
        bump(scope(demo) + ".ambiguities.declined");
    }

    public void deleted() {
        bump("real.sessions.deleted");
    }

    public long count(String key) {
        AtomicLong counter = counters.get(key);
        return counter == null ? 0 : counter.get();
    }

    /** Everything, for the operations page. Sorted so it reads the same way twice. */
    public Map<String, Long> snapshot() {
        return new java.util.TreeMap<>(counters.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));
    }

    private void bump(String key) {
        counters.computeIfAbsent(key, name -> new AtomicLong()).incrementAndGet();
    }

    private static String scope(boolean demo) {
        return demo ? "demo" : "real";
    }

    /**
     * Turns a reason into a bounded label.
     *
     * <p>Bounded matters. The reason can come from an exception message, and an unbounded key
     * space would let a crafted upload create a counter per request, which is both a memory
     * leak and a way to write arbitrary text into the metrics.
     */
    private static String slug(String text) {
        if (text == null || text.isBlank()) {
            return "unknown";
        }
        String slug = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-|-$", "");
        return slug.isBlank() ? "unknown" : slug.substring(0, Math.min(slug.length(), 40));
    }
}
