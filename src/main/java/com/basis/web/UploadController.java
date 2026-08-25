package com.basis.web;

import com.basis.importer.BrokerProfiles;
import com.basis.importer.StatementFormatException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload a statement, see your breaks, leave.
 *
 * <p>No account, no signup, no email. That is not a missing feature to add later: it is the
 * only version of this that anybody would use. Asking a stranger to create an account before
 * showing them anything, in exchange for their brokerage history, is asking for a level of
 * trust that has not been earned yet and cannot be earned by a signup form.
 *
 * <p>So the flow owes them something in return, which is that the data is held in memory for
 * two hours, is deleted the moment they ask, and is never written to a database. The privacy
 * page says so and this code is what makes it true.
 */
@Controller
@org.springframework.context.annotation.Profile("web")
public class UploadController {

    private final BreakFinder finder;
    private final SessionStore sessions;
    private final SessionCookie cookies;
    private final UploadReader uploads;
    private final DemoStatement demo;
    private final Usage usage;
    private final WebConfig.Limits limits;

    public UploadController(BreakFinder finder, SessionStore sessions, SessionCookie cookies,
            UploadReader uploads, DemoStatement demo, Usage usage, WebConfig.Limits limits) {
        this.finder = finder;
        this.sessions = sessions;
        this.cookies = cookies;
        this.uploads = uploads;
        this.demo = demo;
        this.usage = usage;
        this.limits = limits;
    }

    @GetMapping("/")
    public String landing(Model model, HttpServletRequest request) {
        model.addAttribute("brokers", BrokerProfiles.available());
        model.addAttribute("limits", limits);
        model.addAttribute("hasSession", session(request).isPresent());
        return "landing";
    }

    /**
     * The whole computation, in one request.
     *
     * <p>Synchronous on purpose. A history of a few thousand rows is a few tens of
     * milliseconds, and an upload that returns an answer is a better experience than one that
     * returns a job id. The stage timings are reported so a genuinely slow file can still say
     * where the time went.
     */
    @PostMapping("/check")
    public String check(
            @RequestParam("history") MultipartFile history,
            @RequestParam(value = "positions", required = false) MultipartFile positions,
            @RequestParam(value = "broker", required = false) String brokerOverride,
            Model model, HttpServletRequest request, HttpServletResponse response) {

        UploadReader.Read read = uploads.read(history, "transaction history");
        uploads.refuseIfItIsAPositionFile(read.headerLine());

        String broker = chooseBroker(brokerOverride, read.headerLine());
        List<String> positionLines = positions == null || positions.isEmpty()
                ? List.of()
                : uploads.read(positions, "position statement").lines();
        String positionName = positions == null || positions.isEmpty()
                ? null : uploads.read(positions, "position statement").filename();

        UploadedStatement upload = UploadedStatement.of(broker, read.lines(), positionLines,
                read.filename(), positionName, false);
        BreakFinder.Result result = finder.find(upload);

        if (result.rowsRead() == 0) {
            usage.parseFailed(broker, "no transactions", false);
            throw new UploadReader.RejectedUpload(
                    "basis read that file but found no transactions in it.",
                    "Some brokers export an empty file when the date range contains no activity."
                            + " Widen the range and download it again.");
        }

        usage.parsed(broker, result, false);
        String id = sessions.put(upload);
        cookies.write(response, id, request.isSecure(), (int) SessionStore.LIFETIME.toSeconds());
        return "redirect:/breaks";
    }

    @GetMapping("/demo")
    public String startDemo(Model model, HttpServletRequest request, HttpServletResponse response) {
        UploadedStatement seeded = demo.build();
        BreakFinder.Result result = finder.find(seeded);
        usage.parsed("demo", result, true);
        String id = sessions.put(seeded);
        cookies.write(response, id, request.isSecure(), (int) SessionStore.LIFETIME.toSeconds());
        return "redirect:/breaks";
    }

    @GetMapping("/breaks")
    public String breaks(Model model, HttpServletRequest request) {
        Optional<String> id = session(request);
        if (id.isEmpty()) {
            return "redirect:/?expired=1";
        }
        UploadedStatement upload = sessions.get(id.get()).orElse(null);
        if (upload == null) {
            return "redirect:/?expired=1";
        }
        BreakFinder.Result result = finder.find(upload);
        usage.viewed(upload.demo());

        model.addAttribute("upload", upload);
        model.addAttribute("result", result);
        model.addAttribute("expiresAt", sessions.expiryOf(id.get()).orElse(null));
        model.addAttribute("brokerName", BrokerProfiles.load(upload.broker()).name());
        return "breaks";
    }

    /** Records the user's decision about an ambiguous corporate action and recomputes. */
    @PostMapping("/resolve")
    public String resolve(
            @RequestParam("kind") String kind,
            @RequestParam("symbol") String symbol,
            @RequestParam("detail") String detail,
            @RequestParam("on") String on,
            Model model, HttpServletRequest request) {

        Optional<String> id = session(request);
        if (id.isEmpty()) {
            return "redirect:/?expired=1";
        }
        UploadedStatement upload = sessions.get(id.get()).orElse(null);
        if (upload == null) {
            return "redirect:/?expired=1";
        }
        if (kind.isBlank()) {
            // "It was something missing instead." Nothing to apply, and saying so is a real
            // answer rather than a dismissal: the break stays, correctly.
            usage.choiceDeclined(upload.demo());
            return "redirect:/breaks#kept";
        }
        UploadedStatement decided = upload.plus(new UploadedStatement.AppliedChoice(
                kind, symbol, detail, LocalDate.parse(on)));
        try {
            // Tried before it is stored. A choice the ledger refuses would otherwise be
            // saved against the session and thrown on every subsequent page load, which
            // turns one bad decision into a results page the user can never open again.
            finder.find(decided);
        } catch (RuntimeException refused) {
            usage.parseFailed("choice", "rejected choice", upload.demo());
            model.addAttribute("choiceProblem", refused.getMessage());
            return "redirect:/breaks?refused=1";
        }
        sessions.replace(id.get(), decided);
        usage.choiceApplied(kind, upload.demo());
        return "redirect:/breaks";
    }

    @PostMapping("/delete")
    public String delete(HttpServletRequest request, HttpServletResponse response) {
        session(request).ifPresent(sessions::delete);
        cookies.clear(response, request.isSecure());
        usage.deleted();
        return "redirect:/?deleted=1";
    }

    /** The break list as CSV, so it can go into a spreadsheet or an email to a broker. */
    @GetMapping(value = "/breaks.csv", produces = "text/csv")
    @ResponseBody
    public String export(HttpServletRequest request, HttpServletResponse response) {
        UploadedStatement upload = session(request).flatMap(sessions::get).orElse(null);
        if (upload == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "no session\n";
        }
        response.setHeader("Content-Disposition", "attachment; filename=\"basis-breaks.csv\"");
        StringBuilder csv = new StringBuilder(
                "as_of,account,symbol,break_type,cause,confident,broker_quantity,"
                        + "computed_quantity,explanation,suggested_action\n");
        for (com.basis.reconcile.BreakRecord record : finder.find(upload).breaks()) {
            csv.append(cell(record.asOf().toString()))
                    .append(',').append(cell(record.account().name()))
                    .append(',').append(cell(record.commodity().symbol()))
                    .append(',').append(cell(record.type().name()))
                    .append(',').append(cell(record.cause().code()))
                    .append(',').append(record.cause().confident() ? "confirmed" : "suspected")
                    .append(',').append(cell(record.brokerQuantity().toString()))
                    .append(',').append(cell(record.computedQuantity().toString()))
                    .append(',').append(cell(record.cause().explanation()))
                    .append(',').append(cell(record.cause().suggestedAction()))
                    .append('\n');
        }
        return csv.toString();
    }

    @GetMapping(value = "/privacy")
    public String privacy(Model model) {
        model.addAttribute("lifetimeHours", SessionStore.LIFETIME.toHours());
        return "privacy";
    }

    /**
     * Every refusal renders the upload page again, with the problem and the next step.
     *
     * <p>Not an error page. Somebody whose first upload failed is one click from leaving, and
     * sending them to a dead end with a stack trace guarantees it. The form is still there,
     * still filled in as far as it can be, with a sentence saying what to change.
     */
    @ExceptionHandler(UploadReader.RejectedUpload.class)
    public String rejected(UploadReader.RejectedUpload rejection, Model model) {
        usage.parseFailed("unknown", rejection.getMessage(), false);
        model.addAttribute("problem", rejection.getMessage());
        model.addAttribute("nextStep", rejection.nextStep());
        model.addAttribute("brokers", BrokerProfiles.available());
        model.addAttribute("limits", limits);
        return "landing";
    }

    /**
     * A statement basis could read but not understand, which is a different failure.
     *
     * <p>The importer's message already names the row and the exact wording it did not
     * recognise, and for a corporate action it names the command instead. That message is the
     * most useful thing on the screen, so it is shown rather than summarised.
     */
    @ExceptionHandler(StatementFormatException.class)
    public String unreadable(StatementFormatException failure, Model model) {
        usage.parseFailed("unknown", "statement format", false);
        model.addAttribute("problem", "basis stopped on a row it could not read.");
        model.addAttribute("nextStep", failure.getMessage());
        model.addAttribute("brokers", BrokerProfiles.available());
        model.addAttribute("limits", limits);
        return "landing";
    }

    /**
     * A sale of something the uploaded history never shows being bought.
     *
     * <p>The most likely way a real first upload fails, and it is not a bug in the file. A
     * Fidelity download covers ninety days. If you sold something in that window that you
     * bought two years ago, the purchase is in a different file, and basis will not invent a
     * cost basis for it because that number is the person's taxable gain.
     *
     * <p>It gets its own message because the generic one ("basis hit a problem it did not
     * expect") is exactly wrong here: this is entirely expected, the file is fine, and the
     * next step is concrete. Reaching this through the unexpected handler was how it behaved
     * the first time a real export was uploaded.
     */
    @ExceptionHandler(com.basis.ledger.lot.InsufficientLotsException.class)
    public String soldSomethingBoughtEarlier(
            com.basis.ledger.lot.InsufficientLotsException failure, Model model) {
        usage.parseFailed("unknown", "purchase outside window", false);
        model.addAttribute("problem",
                "That history sells something it never shows you buying.");
        model.addAttribute("nextStep",
                "This is normal and the file is fine. Brokers cap each download at a date range,"
                        + " so a sale in this file whose purchase is older sits in a different"
                        + " one. Download the earlier date range as well and upload that first,"
                        + " or upload a range that starts before you opened the position."
                        + " basis will not guess what you paid, because that number is your"
                        + " taxable gain. The detail: " + failure.getMessage());
        model.addAttribute("brokers", BrokerProfiles.available());
        model.addAttribute("limits", limits);
        return "landing";
    }

    /**
     * Anything else that went wrong, said in a sentence rather than as a JSON stack trace.
     *
     * <p>Without this, an unexpected failure reaches the browser as Spring's default error
     * body, which is a 500 and a timestamp. Somebody who has just uploaded their trading
     * history deserves to be told that basis broke rather than left to read a status code.
     */
    @ExceptionHandler(RuntimeException.class)
    public String unexpected(RuntimeException failure, Model model) {
        usage.parseFailed("unknown", "internal", false);
        model.addAttribute("problem", "basis hit a problem it did not expect while working on that.");
        model.addAttribute("nextStep", failure.getMessage() == null
                ? "Nothing was stored. Try uploading the file again."
                : failure.getMessage());
        model.addAttribute("brokers", BrokerProfiles.available());
        model.addAttribute("limits", limits);
        return "landing";
    }

    private String chooseBroker(String override, String headerLine) {
        if (override != null && !override.isBlank() && !override.equals("auto")) {
            return override;
        }
        return BrokerDetector.detect(headerLine)
                .map(BrokerDetector.Guess::broker)
                .orElseThrow(() -> new UploadReader.RejectedUpload(
                        "basis could not tell which broker wrote that file.",
                        "Pick your broker from the list and upload it again. If it is not listed,"
                                + " adding one is a config file rather than code, and the README"
                                + " explains how."));
    }

    private Optional<String> session(HttpServletRequest request) {
        return cookies.read(request);
    }

    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
