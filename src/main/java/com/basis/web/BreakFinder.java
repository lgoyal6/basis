package com.basis.web;

import com.basis.cli.PositionsFile;
import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Lot;
import com.basis.domain.Quantity;
import com.basis.domain.event.LedgerEvent;
import com.basis.importer.AssertedEntries;
import com.basis.importer.BrokerProfile;
import com.basis.importer.BrokerProfiles;
import com.basis.importer.StatementParser;
import com.basis.importer.StatementRow;
import com.basis.importer.StatementRowMapper;
import com.basis.ledger.Ledger;
import com.basis.ledger.LedgerAccounts;
import com.basis.ledger.LedgerState;
import com.basis.reconcile.BreakRecord;
import com.basis.reconcile.BrokerSnapshot;
import com.basis.reconcile.Reconciler;
import com.basis.reconcile.SnapshotScope;
import com.basis.reconcile.SplitCalendar;
import com.basis.reference.CommodityCatalog;
import com.basis.reference.SymbolMapping;
import com.basis.reference.SymbolMappingFile;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Turns an uploaded statement into a break list, computing entirely in memory.
 *
 * <p>Calls the same parser, the same mapper, the same ledger and the same reconciler the CLI
 * calls. That is the whole design of the web layer: it has no arithmetic of its own, so
 * there is no second answer for it to give. If the browser shows a gain, a person running
 * the CLI over the same file gets the same gain, because it is literally the same code
 * reaching the same conclusion.
 *
 * <p>Nothing is written to the database. The ledger here is an object, not a table, so an
 * upload leaves no trace anywhere except the {@link SessionStore}'s map. The one thing this
 * does read from Postgres is split history, which is public market data about companies and
 * not information about the person who uploaded anything.
 *
 * <p>Recomputed on every request rather than cached. A history of a few thousand rows takes
 * milliseconds, and the alternative is a stored answer that can quietly disagree with what
 * the ledger would say now.
 */
@Service
// Web only. Scanned into a CLI context these would demand beans the web profile
// provides, which is how adding the web layer broke every Spring test at once.
@org.springframework.context.annotation.Profile("web")
public class BreakFinder {

    /** Where money arriving from outside the brokerage comes from. Same default as the CLI. */
    private static final Account EXTERNAL = Account.of("Assets:Bank:External");

    private final SplitCalendar splits;
    private final CommodityCatalog commodities;
    private final SymbolMapping renames;

    public BreakFinder(SplitCalendar splits) {
        this.splits = splits;
        this.commodities = CommodityCatalog.load();
        this.renames = SymbolMappingFile.load(java.nio.file.Path.of("config/symbol-changes.csv"));
    }

    /** One named phase of the work, so a page can say what is taking the time. */
    public record Stage(String name, String detail, long millis) {
    }

    public record Result(
            List<BreakRecord> breaks,
            List<Holding> positions,
            List<Stage> stages,
            int rowsRead,
            int eventsRecorded,
            int transactions,
            boolean reconciled,
            List<Ambiguities.Ambiguity> ambiguities) {

        /** Breaks that carry evidence, which are the ones worth acting on first. */
        public long confirmedCount() {
            return breaks.stream().filter(record -> record.cause().confident()).count();
        }

        public boolean isClean() {
            return breaks.isEmpty();
        }

        public long totalMillis() {
            return stages.stream().mapToLong(Stage::millis).sum();
        }
    }

    /** One line of the position summary, as the ledger computed it. */
    public record Holding(String symbol, Quantity quantity, String costBasis, int lots) {
    }

    public Result find(UploadedStatement upload) {
        List<Stage> stages = new ArrayList<>();
        long mark = System.nanoTime();

        BrokerProfile profile = BrokerProfiles.load(upload.broker());
        List<StatementRow> rows = new StatementParser(profile)
                .parse(upload.historyLines(), name(upload.historyFilename()));
        stages.add(stage("Parse", rows.size() + " row(s) read from " + profile.name(), mark));
        mark = System.nanoTime();

        StatementRowMapper mapper = new StatementRowMapper(profile, upload.account(),
                EXTERNAL, renames, commodities, name(upload.historyFilename()));
        List<LedgerEvent> events = new ArrayList<>();
        for (StatementRow row : rows) {
            events.addAll(mapper.toEvents(row));
        }
        Ledger ledger = new Ledger();
        int transactions = 0;
        for (LedgerEvent event : events) {
            ledger.record(event);
            transactions++;
        }
        stages.add(stage("Build the ledger",
                events.size() + " event(s), " + transactions + " transaction(s)", mark));
        mark = System.nanoTime();

        // Decisions the user made about ambiguous corporate actions, replayed after the
        // statement because that is the order in which they happened.
        for (UploadedStatement.AppliedChoice choice : upload.choices()) {
            ledger.record(toEvent(choice, upload.account()));
            transactions++;
        }
        stages.add(stage("Corporate actions",
                upload.choices().isEmpty()
                        ? "none needing a decision"
                        : upload.choices().size() + " applied from your choices", mark));
        mark = System.nanoTime();

        LedgerState state = ledger.state();
        List<BreakRecord> breaks = List.of();
        boolean reconciled = false;
        if (upload.hasPositions()) {
            BrokerSnapshot snapshot = PositionsFile.parse(upload.positionLines(),
                    name(upload.positionFilename()), upload.account(), LocalDate.now(),
                    SnapshotScope.SECURITIES_ONLY);
            // A demo brings its own split history so it never reads or writes the shared
            // reference cache. See DemoSplits for why that is not just tidiness.
            SplitCalendar calendar = upload.demo() ? DemoSplits.CALENDAR : splits;
            breaks = new Reconciler(calendar, renames).reconcile(state, snapshot);
            reconciled = true;
            stages.add(stage("Reconcile",
                    snapshot.positions().size() + " reported position(s) compared", mark));
        } else {
            stages.add(stage("Reconcile",
                    "skipped, because no position statement was uploaded to compare against", mark));
        }

        breaks = sortByConfidence(breaks);
        return new Result(breaks, holdings(state, upload.account()), stages, rows.size(),
                events.size(), transactions, reconciled, Ambiguities.of(breaks, state));
    }

    /**
     * Confident findings first.
     *
     * <p>A break basis can prove is worth more of somebody's attention than one it merely
     * suspects, and the first screen of a long list is the only part many people read. Within
     * each group the order is the reconciler's, which is by date and symbol and therefore
     * stable across runs.
     */
    private static List<BreakRecord> sortByConfidence(List<BreakRecord> breaks) {
        List<BreakRecord> sorted = new ArrayList<>(breaks);
        sorted.sort((left, right) -> {
            int byConfidence = Boolean.compare(right.cause().confident(), left.cause().confident());
            if (byConfidence != 0) {
                return byConfidence;
            }
            return Integer.compare(rank(left), rank(right));
        });
        return List.copyOf(sorted);
    }

    /** Actionable causes above ones that only say something is missing. */
    private static int rank(BreakRecord record) {
        return switch (record.cause().code()) {
            case "UNAPPLIED_SPLIT", "UNAPPLIED_REVERSE_SPLIT" -> 0;
            case "TICKER_RENAMED" -> 1;
            case "RATIO_WITHOUT_KNOWN_SPLIT" -> 2;
            case "MISSING_ACQUISITION", "MISSING_DISPOSAL" -> 3;
            case "BASIS_DRIFT" -> 4;
            case "UNKNOWN_HOLDING", "STALE_HOLDING" -> 5;
            default -> 6;
        };
    }

    private List<Holding> holdings(LedgerState state, Account broker) {
        Map<String, Holding> byCommodity = new LinkedHashMap<>();
        for (Lot lot : state.allLots()) {
            if (!lot.account().name().startsWith(broker.name())) {
                continue;
            }
            String symbol = lot.commodity().symbol();
            Holding existing = byCommodity.get(symbol);
            Quantity quantity = existing == null ? lot.remainingQuantity()
                    : existing.quantity().plus(lot.remainingQuantity());
            BigDecimal basis = lot.remainingQuantity().value().multiply(lot.unitCost().value());
            BigDecimal running = existing == null ? basis
                    : new BigDecimal(existing.costBasis()).add(basis);
            byCommodity.put(symbol, new Holding(symbol, quantity,
                    running.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString(),
                    existing == null ? 1 : existing.lots() + 1));
        }
        return List.copyOf(byCommodity.values());
    }

    /**
     * Builds the event a choice stands for.
     *
     * <p>The commodity is resolved through the catalog, not assumed to be an equity. A
     * commodity's class is part of its identity, so a mutual fund named as an equity is a
     * different commodity: the corporate action found no lots to restate, applied silently to
     * nothing, and the break it was supposed to fix stayed exactly where it was.
     */
    private LedgerEvent toEvent(UploadedStatement.AppliedChoice choice, Account account) {
        Commodity commodity = commodities.resolve(choice.symbol());
        return switch (choice.kind()) {
            case "split" -> {
                long[] ratio = AssertedEntries.ratio(choice.detail());
                yield AssertedEntries.split(account, commodity, ratio[0], ratio[1], choice.on());
            }
            case "reverse-split" -> {
                long[] ratio = AssertedEntries.ratio(choice.detail());
                yield AssertedEntries.reverseSplit(account, commodity, ratio[0], ratio[1], choice.on());
            }
            default -> throw new IllegalArgumentException(
                    "no corporate action of kind '" + choice.kind() + "' can be applied from a choice");
        };
    }

    private static Stage stage(String name, String detail, long startNanos) {
        return new Stage(name, detail, Math.max(1, (System.nanoTime() - startNanos) / 1_000_000));
    }

    private static String name(String filename) {
        return filename == null || filename.isBlank() ? "upload.csv" : filename;
    }
}
