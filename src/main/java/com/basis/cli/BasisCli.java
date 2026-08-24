package com.basis.cli;

import com.basis.domain.Account;
import com.basis.ledger.LedgerState;
import com.basis.ledger.PositionKey;
import com.basis.persistence.BreakRecordRepository;
import com.basis.persistence.DerivedStateProjector;
import com.basis.persistence.DerivedStateRepository;
import com.basis.persistence.ReferenceDataRepository;
import com.basis.persistence.StartupRecovery;
import com.basis.reconcile.BreakRecord;
import com.basis.reconcile.BreakStatus;
import com.basis.reconcile.BrokerSnapshot;
import com.basis.reconcile.Reconciler;
import com.basis.reconcile.SnapshotScope;
import com.basis.reference.SplitRefreshService;
import com.basis.reference.SymbolMapping;
import com.basis.reference.SymbolMappingFile;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
    private final CliOutput out;

    private int exitCode = OK;

    public BasisCli(
            DerivedStateProjector projector,
            DerivedStateRepository derived,
            BreakRecordRepository breaks,
            ReferenceDataRepository referenceData,
            SplitRefreshService refresh,
            StartupRecovery recovery,
            CliOutput out) {
        this.projector = projector;
        this.derived = derived;
        this.breaks = breaks;
        this.referenceData = referenceData;
        this.refresh = refresh;
        this.recovery = recovery;
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

    /** What the ledger currently believes, and how much of it is unexplained. */
    private int status() {
        out.heading("positions");
        LedgerState state = projector.project();
        if (state.positions().isEmpty()) {
            out.line("  none. Nothing has been imported yet.");
        }
        for (Map.Entry<PositionKey, com.basis.domain.Quantity> entry : state.positions().entrySet()) {
            out.line("  " + entry.getKey().account() + "  " + entry.getValue() + " " + entry.getKey().commodity());
        }

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
            breaks.recordAll(found);
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
     * Static so that {@code --help} can be answered before the application context starts.
     * Every other command needs a database, and making someone stand up Postgres to be told
     * what the commands are would be a poor introduction.
     */
    public static void printUsage(CliOutput out) {
        out.line("basis, a ledger that argues with your broker");
        out.blank();
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
