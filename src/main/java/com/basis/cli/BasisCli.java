package com.basis.cli;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Money;
import com.basis.domain.Price;
import com.basis.domain.Quantity;
import com.basis.domain.event.LedgerEvent;
import com.basis.importer.AssertedEntries;
import com.basis.importer.BrokerProfile;
import com.basis.importer.BrokerProfiles;
import com.basis.importer.ImportReport;
import com.basis.importer.ImportService;
import com.basis.ledger.LedgerAccounts;
import com.basis.ledger.LedgerState;
import com.basis.ledger.PositionKey;
import com.basis.persistence.BreakRecordRepository;
import com.basis.persistence.DerivedStateProjector;
import com.basis.persistence.DerivedStateRepository;
import com.basis.persistence.ReferenceDataRepository;
import com.basis.persistence.StartupRecovery;
import com.basis.reconcile.BreakRecord;
import com.basis.reconcile.KnownSplit;
import com.basis.reconcile.ProbableCause;
import com.basis.reconcile.Ratio;
import com.basis.reconcile.RatioDetector;
import com.basis.reconcile.BreakStatus;
import com.basis.reconcile.BrokerSnapshot;
import com.basis.reconcile.Reconciler;
import com.basis.reconcile.SnapshotScope;
import com.basis.reference.SplitRefreshService;
import com.basis.reference.CommodityCatalog;
import com.basis.reference.SymbolMapping;
import com.basis.reference.SymbolMappingFile;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * What you actually run.
 *
 * <p>Hand rolled rather than built on a command line library, because the whole surface is
 * seven verbs and a shell parser is a poor reason to add a dependency to a project that has
 * so far added none it did not need.
 *
 * <p>Exit codes are meant to be used by something other than a person:
 *
 * <ul>
 *   <li>{@code 0} the command did what it was asked
 *   <li>{@code 1} it failed
 *   <li>{@code 2} it was invoked wrongly
 *   <li>{@code 3} reconcile ran and found breaks. Distinct from failure on purpose, so a
 *       pipeline can treat "your broker and this ledger disagree" as its own outcome
 * </ul>
 */
@Component
public class BasisCli implements ApplicationRunner, org.springframework.boot.ExitCodeGenerator {

    /**
     * Options that take a value, so {@code --cost 250.00} can be written the way people
     * actually write it.
     *
     * <p>Spring only understands {@code --name=value}. Everything else on a command line
     * accepts a space, and the README documented the space form before anyone tried it, so
     * the value silently failed to bind and the command complained about its own usage.
     *
     * <p>Listed explicitly rather than inferred, because joining any option to whatever
     * follows it would turn {@code reconcile ACC --dry-run file.csv} into a flag whose value
     * is the filename, and lose the file.
     */
    private static final Set<String> OPTIONS_WITH_VALUES = Set.of(
            "cost", "kind", "on", "as-of", "external", "renames", "brokers", "commodities", "note");

    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;
    static final int BREAKS_FOUND = 3;

    private final DerivedStateProjector projector;
    private final DerivedStateRepository derived;
    private final BreakRecordRepository breaks;
    private final ReferenceDataRepository referenceData;
    private final SplitRefreshService refresh;
    private final StartupRecovery recovery;
    private final ImportService importer;
    private final CliOutput out;

    private int exitCode = OK;

    public BasisCli(
            DerivedStateProjector projector,
            DerivedStateRepository derived,
            BreakRecordRepository breaks,
            ReferenceDataRepository referenceData,
            SplitRefreshService refresh,
            StartupRecovery recovery,
            ImportService importer,
            CliOutput out) {
        this.projector = projector;
        this.derived = derived;
        this.breaks = breaks;
        this.referenceData = referenceData;
        this.refresh = refresh;
        this.recovery = recovery;
        this.importer = importer;
        this.out = out;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> words = args.getNonOptionArgs();
        if (words.isEmpty()) {
            printUsage();
            exitCode = USAGE;
            return;
        }
        try {
            exitCode = dispatch(words.get(0), words.subList(1, words.size()), args);
        } catch (IllegalArgumentException e) {
            out.error(e.getMessage());
            exitCode = USAGE;
        } catch (RuntimeException e) {
            out.error(e.getClass().getSimpleName() + ": " + e.getMessage());
            exitCode = FAILED;
        }
    }

    private int dispatch(String command, List<String> rest, ApplicationArguments args) {
        return switch (command) {
            case "import" -> importStatement(rest, args);
            case "open" -> openingBalance(rest, args);
            case "apply" -> apply(rest, args);
            case "status" -> status();
            case "breaks" -> listBreaks(rest);
            case "settle" -> settle(rest, args);
            case "reconcile" -> reconcile(rest, args);
            case "refresh-splits" -> refreshSplits(rest);
            case "rebuild" -> rebuild();
            case "recover" -> recover();
            default -> {
                out.error("unknown command '" + command + "'");
                printUsage();
                yield USAGE;
            }
        };
    }

    /**
     * Reads a broker statement into the ledger.
     *
     * <p>The broker is named on the command line rather than sniffed from the file. Guessing
     * which broker produced a CSV and guessing wrong means importing a column of prices as
     * quantities, which reconciles cleanly and is completely wrong.
     */
    private int importStatement(List<String> rest, ApplicationArguments args) {
        String usage = "import <broker> <account> <statement.csv> [--external ACCOUNT]";
        String broker = requireArg(rest, 0, usage);
        Account account = Account.of(requireArg(rest, 1, usage));
        Path file = Path.of(requireArg(rest, 2, usage));

        BrokerProfile profile = BrokerProfiles.load(
                Path.of(optional(args, "brokers").orElse("config/brokers")), broker);
        Account external = Account.of(optional(args, "external").orElse("Assets:Bank:External"));
        SymbolMapping renames = SymbolMappingFile.load(
                Path.of(optional(args, "renames").orElse("config/symbol-changes.csv")));

        CommodityCatalog catalog = CommodityCatalog.load(
                Path.of(optional(args, "commodities").orElse("config/commodities.csv")));

        ImportReport report =
                importer.importStatement(file, profile, account, external, renames, catalog);
        out.line(report.toString());
        for (String note : report.notes()) {
            out.line("  " + note);
        }
        return OK;
    }

    /**
     * States what an account already held before the imported history begins.
     *
     * <p>Brokers keep a few years. A position older than that has no statement to import, so
     * it has to be asserted or every holding that predates the oldest download reconciles as
     * a security the ledger has never heard of.
     */
    private int openingBalance(List<String> rest, ApplicationArguments args) {
        String usage = "open <account> <symbol> <quantity> [--cost PRICE] [--kind KIND] --on yyyy-mm-dd";
        Account account = Account.of(requireArg(rest, 0, usage));
        String symbol = requireArg(rest, 1, usage);
        BigDecimal quantity = new BigDecimal(requireArg(rest, 2, usage));
        LocalDate on = on(args, usage);
        Commodity commodity = commodityFor(symbol, args);

        LedgerEvent event;
        if (commodity.isCash()) {
            event = AssertedEntries.openingCash(account, Money.of(quantity, commodity.asCurrency()), on);
        } else {
            String cost = optional(args, "cost").orElseThrow(() -> new IllegalArgumentException(
                    "an opening balance of " + symbol + " needs --cost, the price it was acquired at."
                            + " Without a basis every later sale of it reports the wrong gain."));
            event = AssertedEntries.openingSecurity(account, commodity, Quantity.of(quantity),
                    Price.of(new BigDecimal(cost), java.util.Currency.getInstance("USD")), on);
        }
        return recordAsserted(event, "opening balance");
    }

    /**
     * Applies a corporate action the ledger could always handle and nothing could reach.
     *
     * <p>These are asserted rather than parsed. A transaction export reports corporate
     * actions inconsistently and often not at all, and a split read wrongly restates every
     * lot in a position, so basis takes them from a person or from reference data instead of
     * guessing at a row.
     */
    private int apply(List<String> rest, ApplicationArguments args) {
        String action = requireArg(rest, 0,
                "apply split|reverse-split|stock-dividend|spin-off|break ...");
        return switch (action) {
            case "split" -> applySplit(rest, args, false);
            case "reverse-split" -> applySplit(rest, args, true);
            case "stock-dividend" -> applyStockDividend(rest, args);
            case "spin-off" -> applySpinOff(rest, args);
            case "break" -> applyBreak(rest, args);
            case "average-cost" -> applyAverageCost(rest, args);
            default -> throw new IllegalArgumentException("unknown action '" + action
                    + "'. Expected split, reverse-split, stock-dividend, spin-off, average-cost"
                    + " or break.");
        };
    }

    private int applySplit(List<String> rest, ApplicationArguments args, boolean reverse) {
        String name = reverse ? "reverse-split" : "split";
        String usage = "apply " + name + " <account> <symbol> <new:old> --on yyyy-mm-dd"
                + (reverse ? " [--cash-in-lieu AMOUNT]" : "");
        Account account = Account.of(requireArg(rest, 1, usage));
        Commodity commodity = commodityFor(requireArg(rest, 2, usage), args);
        long[] ratio = AssertedEntries.ratio(requireArg(rest, 3, usage));
        LocalDate on = on(args, usage);

        LedgerEvent event = reverse
                ? AssertedEntries.reverseSplit(account, commodity, ratio[0], ratio[1], on)
                : AssertedEntries.split(account, commodity, ratio[0], ratio[1], on);
        int code = recordAsserted(event, name);

        return optional(args, "cash-in-lieu")
                .map(amount -> sellFraction(account, commodity, new BigDecimal(amount), on))
                .orElse(code);
    }

    /**
     * Sells whatever fraction of a share the reverse split left behind.
     *
     * <p>Run after the restatement, against the position it produced, because the fraction
     * only exists once the split has been applied. It is a real disposal and often the one
     * taxable event in a corporate action that a statement never labels as one.
     */
    private int sellFraction(Account account, Commodity commodity, BigDecimal proceeds, LocalDate on) {
        Quantity held = projector.project()
                .position(LedgerAccounts.holding(account, commodity), commodity);
        BigDecimal fraction = held.value().remainder(BigDecimal.ONE);
        if (fraction.signum() == 0) {
            out.line("no fractional share was left, so nothing was sold in lieu");
            return OK;
        }
        return recordAsserted(AssertedEntries.cashInLieu(account, commodity, Quantity.of(fraction),
                AssertedEntries.usd(proceeds), on), "cash in lieu");
    }

    private int applyAverageCost(List<String> rest, ApplicationArguments args) {
        String usage = "apply average-cost <account> <symbol> --on yyyy-mm-dd [--kind MUTUAL_FUND]";
        Account account = Account.of(requireArg(rest, 1, usage));
        Commodity commodity = commodityFor(requireArg(rest, 2, usage), args);
        return recordAsserted(
                AssertedEntries.averageCost(account, commodity, on(args, usage)),
                "average cost election for " + commodity);
    }

    private int applyStockDividend(List<String> rest, ApplicationArguments args) {
        String usage = "apply stock-dividend <account> <symbol> <shares> --on yyyy-mm-dd";
        Account account = Account.of(requireArg(rest, 1, usage));
        Commodity commodity = commodityFor(requireArg(rest, 2, usage), args);
        Quantity shares = Quantity.of(new BigDecimal(requireArg(rest, 3, usage)));
        return recordAsserted(
                AssertedEntries.stockDividend(account, commodity, shares, on(args, usage)),
                "stock dividend");
    }

    private int applySpinOff(List<String> rest, ApplicationArguments args) {
        String usage = "apply spin-off <account> <parent> <child> <shares-per-parent-share>"
                + " <parent-basis-fraction> --on yyyy-mm-dd";
        Account account = Account.of(requireArg(rest, 1, usage));
        Commodity parent = commodityFor(requireArg(rest, 2, usage), args);
        Commodity child = commodityFor(requireArg(rest, 3, usage), args);
        Quantity perShare = Quantity.of(new BigDecimal(requireArg(rest, 4, usage)));
        BigDecimal fraction = new BigDecimal(requireArg(rest, 5, usage));
        return recordAsserted(
                AssertedEntries.spinOff(account, parent, child, perShare, fraction, on(args, usage)),
                "spin off");
    }

    /**
     * Does what a break said to do.
     *
     * <p>The reconciler already worked this out: it found the ratio, matched it against the
     * split history, named the date and printed "apply the 4 for 1 split of AAPL dated
     * 2020-08-31". Until this command existed that instruction could not be followed, which
     * made the product's headline finding a dead end.
     *
     * <p>Only acts on a break whose cause is confirmed. A suspicion is a ratio with nothing
     * behind it, and restating every lot in a position on the strength of a coincidence is
     * exactly the kind of confident wrong move this project refuses to make.
     *
     * <p>The fix is re-derived from reference data rather than read out of the break's
     * sentence. The reference data is the authority on what the split was; the break is a
     * report about a disagreement.
     */
    private int applyBreak(List<String> rest, ApplicationArguments args) {
        long id = Long.parseLong(requireArg(rest, 1, "apply break <break-id>"));
        BreakRecord found = breaks.findOpen(id).orElseThrow(() -> new IllegalArgumentException(
                "no open break with id " + id));

        if (!found.cause().confident()) {
            throw new IllegalArgumentException("break " + id + " is a suspicion, not a finding: "
                    + found.cause().explanation()
                    + " Applying a corporate action on the strength of a ratio alone would be a guess."
                    + " Refresh the reference data and reconcile again, or apply the action explicitly.");
        }
        if (!found.cause().code().equals(ProbableCause.UNAPPLIED_SPLIT)
                && !found.cause().code().equals(ProbableCause.UNAPPLIED_REVERSE_SPLIT)) {
            throw new IllegalArgumentException("break " + id + " is a " + found.cause().code()
                    + ", which basis cannot apply for you. Its suggestion was: "
                    + found.cause().suggestedAction());
        }

        Ratio ratio = RatioDetector.between(found.computedQuantity(), found.brokerQuantity())
                .orElseThrow(() -> new IllegalStateException("break " + id
                        + " no longer reduces to a corporate action ratio; reconcile again"));
        Account broker = brokerRootOf(found.account());
        LocalDate earliest = earliestAcquisition(broker, found.commodity()).orElse(found.asOf());

        KnownSplit split = referenceData.coverageBetween(found.commodity(), earliest, found.asOf())
                .matching(ratio)
                .orElseThrow(() -> new IllegalStateException("the split that explained break " + id
                        + " is no longer in the reference data; refresh it and reconcile again"));

        LedgerEvent event = split.numerator() > split.denominator()
                ? AssertedEntries.split(broker, found.commodity(), split.numerator(), split.denominator(),
                        split.date())
                : AssertedEntries.reverseSplit(broker, found.commodity(), split.numerator(),
                        split.denominator(), split.date());

        recordAsserted(event, "the " + split.ratio() + " split of " + found.commodity()
                + " dated " + split.date());
        breaks.settle(id, BreakStatus.ACCEPTED,
                "applied the " + split.ratio() + " split dated " + split.date());
        out.line("break " + id + " settled as ACCEPTED. Reconcile again to confirm nothing is left.");
        return OK;
    }

    /** A holding lives at broker:SYMBOL, so the broker is its parent. */
    private static Account brokerRootOf(Account holding) {
        List<String> segments = holding.segments();
        return Account.of(String.join(":", segments.subList(0, segments.size() - 1)));
    }

    private java.util.Optional<LocalDate> earliestAcquisition(Account broker, Commodity commodity) {
        return projector.project().openLots(LedgerAccounts.holding(broker, commodity), commodity).stream()
                .map(com.basis.domain.Lot::acquisitionDate)
                .min(LocalDate::compareTo);
    }

    private int recordAsserted(LedgerEvent event, String what) {
        ImportReport report = importer.recordAsserted(event, Path.of(what.replace(' ', '-')));
        out.line(report.changedAnything()
                ? what + " applied"
                : what + " was already recorded, so nothing changed");
        return OK;
    }

    /**
     * A commodity as the catalog declares it, unless the command says otherwise.
     *
     * <p>A commodity's class is part of its identity, so a fund imported as an equity and an
     * election made against it as a fund are two different things and the election would find
     * nothing to average. Both paths read the same catalog so that cannot happen.
     */
    private static Commodity commodityFor(String symbol, ApplicationArguments args) {
        String kind = optional(args, "kind").orElse("");
        if (!kind.isBlank()) {
            return AssertedEntries.commodity(symbol, kind);
        }
        return CommodityCatalog.load(
                Path.of(optional(args, "commodities").orElse("config/commodities.csv")))
                .resolve(symbol);
    }

    private static LocalDate on(ApplicationArguments args, String usage) {
        return LocalDate.parse(optional(args, "on")
                .orElseThrow(() -> new IllegalArgumentException("usage: basis " + usage)));
    }

    /** What the ledger currently believes, and how much of it is unexplained. */
    private int status() {
        LedgerState state = projector.project();
        // Split rather than listed together. Every account in a double entry ledger holds a
        // position, including the income and expense accounts, so an undivided list opens
        // with "Income:Dividends:AAPL -24 USD" and buries what someone actually owns.
        out.heading("holdings");
        if (state.positions().isEmpty()) {
            out.line("  none. Nothing has been imported yet.");
        }
        printPositions(state, true);

        out.heading("contra accounts");
        printPositions(state, false);

        out.heading("derived state");
        out.line("  " + derived.countPositions() + " positions, " + derived.countLots() + " lots, "
                + derived.countRealizedGains() + " realized gains");

        out.heading("breaks");
        out.line("  " + breaks.countOpen() + " open");
        for (String summary : breaks.causeSummary()) {
            out.line("  " + summary);
        }

        out.heading("reference data");
        List<String> covered = referenceData.coveredSymbols();
        out.line("  " + covered.size() + " symbols with split history: "
                + (covered.isEmpty() ? "none" : String.join(", ", covered)));
        List<String> unavailable = referenceData.unavailableSymbols();
        if (!unavailable.isEmpty()) {
            out.line("  could not be checked: " + String.join(", ", unavailable));
        }
        return OK;
    }

    /** @param holdings true for the asset side, false for the income, expense and equity side */
    private void printPositions(LedgerState state, boolean holdings) {
        boolean printed = false;
        for (Map.Entry<PositionKey, com.basis.domain.Quantity> entry : state.positions().entrySet()) {
            boolean isAsset = entry.getKey().account().type() == com.basis.domain.AccountType.ASSETS;
            if (isAsset != holdings) {
                continue;
            }
            out.line("  " + entry.getKey().account() + "  " + entry.getValue() + " " + entry.getKey().commodity());
            printed = true;
        }
        if (!printed && !holdings) {
            out.line("  none");
        }
    }

    private int listBreaks(List<String> rest) {
        Account account = Account.of(requireArg(rest, 0, "breaks <account>"));
        List<BreakRecord> open = breaks.findOpen(account);
        if (open.isEmpty()) {
            out.line("no open breaks in " + account);
            return OK;
        }
        for (BreakRecord found : open) {
            printBreak(found);
        }
        out.blank();
        out.line(open.size() + " open break(s)");
        return BREAKS_FOUND;
    }

    private int settle(List<String> rest, ApplicationArguments args) {
        long id = Long.parseLong(requireArg(rest, 0, "settle <break-id> --accept|--reject|--resolved [--note ...]"));
        BreakStatus status = settlementFrom(args);
        String note = optional(args, "note").orElse("");
        breaks.settle(id, status, note);
        out.line("break " + id + " settled as " + status);
        return OK;
    }

    private static BreakStatus settlementFrom(ApplicationArguments args) {
        boolean accept = args.containsOption("accept");
        boolean reject = args.containsOption("reject");
        boolean resolved = args.containsOption("resolved");
        // Counted rather than chained with XOR, which is also true when all three are given.
        int chosen = (accept ? 1 : 0) + (reject ? 1 : 0) + (resolved ? 1 : 0);
        if (chosen != 1) {
            throw new IllegalArgumentException("settle needs exactly one of --accept, --reject or --resolved");
        }
        return accept ? BreakStatus.ACCEPTED : reject ? BreakStatus.REJECTED : BreakStatus.RESOLVED;
    }

    /**
     * The flagship. Read a position snapshot, compare it against the ledger, and write a
     * break for every disagreement with a probable cause attached.
     */
    private int reconcile(List<String> rest, ApplicationArguments args) {
        Account account = Account.of(requireArg(rest, 0,
                "reconcile <account> <positions.csv> --as-of yyyy-mm-dd [--with-cash] [--dry-run]"));
        Path file = Path.of(requireArg(rest, 1,
                "reconcile <account> <positions.csv> --as-of yyyy-mm-dd [--with-cash] [--dry-run]"));
        LocalDate asOf = LocalDate.parse(optional(args, "as-of")
                .orElseThrow(() -> new IllegalArgumentException("reconcile needs --as-of yyyy-mm-dd")));
        SnapshotScope scope = args.containsOption("with-cash")
                ? SnapshotScope.SECURITIES_AND_CASH
                : SnapshotScope.SECURITIES_ONLY;

        SymbolMapping renames = SymbolMappingFile.load(
                Path.of(optional(args, "renames").orElse("config/symbol-changes.csv")));
        BrokerSnapshot snapshot = PositionsFile.read(file, account, asOf, scope);

        List<BreakRecord> found = new Reconciler(referenceData, renames)
                .reconcile(projector.project(), snapshot);

        if (found.isEmpty()) {
            // Clears any break that a previous run left open and that no longer applies.
            breaks.replaceOpen(account, List.of());
            out.line("no breaks: " + snapshot.positions().size() + " reported position(s) agree with the ledger");
            return OK;
        }
        for (BreakRecord record : found) {
            printBreak(record);
        }
        out.blank();
        if (args.containsOption("dry-run")) {
            out.line(found.size() + " break(s), not recorded (--dry-run)");
        } else {
            breaks.replaceOpen(account, found);
            out.line(found.size() + " break(s) recorded");
        }
        return BREAKS_FOUND;
    }

    private int refreshSplits(List<String> rest) {
        SplitRefreshService.RefreshReport report = rest.isEmpty()
                ? refresh.refreshStaleSymbols()
                : refresh.refresh(rest.stream().map(symbol -> symbol.toUpperCase(java.util.Locale.ROOT)).toList());
        out.line(report.toString());
        return report.attempted() == 0 || report.isClean() ? OK : FAILED;
    }

    /** Invariant 7 as an operation: throw the derived tables away and rebuild them from postings. */
    private int rebuild() {
        LedgerState state = projector.rebuild();
        out.line("rebuilt: " + state.positions().size() + " positions, " + state.allLots().size()
                + " lots, " + state.realizedGains().size() + " realized gains");
        out.line("hash: " + derived.hash());
        return OK;
    }

    private int recover() {
        List<Long> rolledBack = recovery.recover();
        out.line(rolledBack.isEmpty()
                ? "nothing was in flight"
                : "rolled back " + rolledBack.size() + " interrupted import batch(es): " + rolledBack);
        return OK;
    }

    /**
     * One break, in the shape a person reads it.
     *
     * <p>Explanation and suggested action are printed as separate lines rather than through
     * {@code BreakRecord.toString}, which runs them together. Printing both and then
     * repeating the action under "next" says the same sentence twice, which is exactly what
     * this did until someone ran it.
     */
    private void printBreak(BreakRecord record) {
        out.blank();
        out.line(record.asOf() + "  " + record.account() + "  " + record.commodity() + "  " + record.type());
        out.line("  broker " + record.brokerQuantity() + ", computed " + record.computedQuantity());
        out.line("  " + record.cause().code()
                + (record.cause().confident() ? " (confirmed)" : " (suspected)"));
        out.line("  " + record.cause().explanation());
        if (!record.cause().suggestedAction().isEmpty()) {
            out.line("  next: " + record.cause().suggestedAction());
        }
    }

    private static String requireArg(List<String> rest, int index, String usage) {
        if (rest.size() <= index) {
            throw new IllegalArgumentException("usage: basis " + usage);
        }
        return rest.get(index);
    }

    private static java.util.Optional<String> optional(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of(values.get(0));
    }

    private void printUsage() {
        printUsage(out);
    }

    /**
     * Rewrites {@code --name value} as {@code --name=value} for the options that take one.
     *
     * <p>Applied before the application starts, so nothing downstream has to know that the
     * two spellings exist.
     */
    public static String[] normaliseOptions(String[] args) {
        List<String> normalised = new java.util.ArrayList<>(args.length);
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            String name = argument.startsWith("--") && !argument.contains("=")
                    ? argument.substring(2)
                    : null;
            boolean takesNext = name != null
                    && OPTIONS_WITH_VALUES.contains(name)
                    && index + 1 < args.length
                    && !args[index + 1].startsWith("--");
            if (takesNext) {
                normalised.add(argument + "=" + args[++index]);
            } else {
                normalised.add(argument);
            }
        }
        return normalised.toArray(new String[0]);
    }

    /**
     * Static so that {@code --help} can be answered before the application context starts.
     * Every other command needs a database, and making someone stand up Postgres to be told
     * what the commands are would be a poor introduction.
     */
    public static void printUsage(CliOutput out) {
        out.line("basis, a ledger that argues with your broker");
        out.blank();
        out.line("  open <account> <symbol> <quantity> [--cost PRICE] --on yyyy-mm-dd");
        out.line("      state what an account already held before the imported history begins");
        out.line("  apply split|reverse-split <account> <symbol> <new:old> --on yyyy-mm-dd");
        out.line("      apply a corporate action the statements do not carry");
        out.line("  apply stock-dividend <account> <symbol> <shares> --on yyyy-mm-dd");
        out.line("  apply spin-off <account> <parent> <child> <per-share> <basis-fraction> --on DATE");
        out.line("  apply average-cost <account> <symbol> --on yyyy-mm-dd");
        out.line("      elect average cost, permitted for mutual funds and not for equities");
        out.line("  apply break <break-id>");
        out.line("      do what a confirmed break said to do, then settle it");
        out.line("  import <broker> <account> <statement.csv> [--external ACCOUNT]");
        out.line("      read a broker statement into the ledger. Brokers available: "
                + String.join(", ", BrokerProfiles.available()));
        out.line("  status");
        out.line("      positions, derived state, open breaks and how current the reference data is");
        out.line("  breaks <account>");
        out.line("      every open break, with what basis thinks caused it");
        out.line("  settle <break-id> --accept|--reject|--resolved [--note \"...\"]");
        out.line("      record a human's judgement on a break");
        out.line("  reconcile <account> <positions.csv> --as-of yyyy-mm-dd [--with-cash] [--dry-run]");
        out.line("      compare a position snapshot against the ledger and record the disagreements");
        out.line("  refresh-splits [SYMBOL...]");
        out.line("      fetch split history, by default for whichever symbols need it most");
        out.line("  rebuild");
        out.line("      truncate derived state and replay it from the posting table");
        out.line("  recover");
        out.line("      resolve any import batch that was interrupted");
        out.blank();
        out.line("exit codes: 0 ok, 1 failed, 2 bad usage, 3 breaks found");
    }
}
