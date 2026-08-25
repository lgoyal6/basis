package com.basis.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.basis.reconcile.SplitCalendar;
import com.basis.reconcile.SplitCoverage;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The web layer's one job: turn an uploaded file into a break list, with no database.
 *
 * <p>No Spring context and no Postgres here on purpose. If these tests needed either, the
 * claim that an upload touches no storage would be untestable, and the claim is the product.
 */
class BreakFinderTest {

    private static final String HEADER = "Run Date,Account,Account Number,Action,Symbol,"
            + "Description,Type,Price ($),Quantity,Commission ($),Fees ($),Accrued Interest ($),"
            + "Amount ($),Settlement Date";

    private static final List<String> HISTORY = List.of(HEADER,
            "01/02/2020,Individual,X,ELECTRONIC FUNDS TRANSFER RECEIVED (Cash),\"\",No Description,"
                    + "Cash,\"\",\"\",\"\",\"\",\"\",5000,01/02/2020",
            "01/03/2020,Individual,X,YOU BOUGHT APPLE INC (AAPL) (Cash),AAPL,APPLE INC,Cash,"
                    + "300.00,10,\"\",\"\",\"\",-3000,01/06/2020");

    @Test
    @DisplayName("a history with no position statement reports holdings and no breaks")
    void withoutPositionsItStillShowsWhatYouHold() {
        BreakFinder.Result result = finder(SplitCalendar.EMPTY)
                .find(UploadedStatement.of("fidelity", HISTORY, List.of(), "h.csv", null, false));

        assertThat(result.reconciled())
                .as("nothing was uploaded to reconcile against, and that is not a failure")
                .isFalse();
        assertThat(result.breaks()).isEmpty();
        assertThat(result.positions()).singleElement().satisfies(holding -> {
            assertThat(holding.symbol()).isEqualTo("AAPL");
            assertThat(holding.quantity().value()).isEqualByComparingTo("10");
            assertThat(holding.costBasis()).isEqualTo("3000.00");
        });
        assertThat(result.stages()).extracting(BreakFinder.Stage::name)
                .containsExactly("Parse", "Build the ledger", "Corporate actions", "Reconcile");
    }

    @Test
    @DisplayName("a broker reporting four times the shares produces a suspected split and a choice")
    void aRatioWithoutEvidenceBecomesAQuestion() {
        BreakFinder.Result result = finder(SplitCalendar.EMPTY).find(
                UploadedStatement.of("fidelity", HISTORY, positions("AAPL,40"), "h.csv", "p.csv", false));

        assertThat(result.breaks()).singleElement().satisfies(record -> {
            assertThat(record.cause().code()).isEqualTo("UNAPPLIED_SPLIT");
            assertThat(record.cause().confident())
                    .as("no split history was checked, so this is arithmetic and not evidence")
                    .isFalse();
        });
        assertThat(result.ambiguities()).singleElement().satisfies(question -> {
            assertThat(question.symbol()).isEqualTo("AAPL");
            assertThat(question.options()).extracting(Ambiguities.Option::kind)
                    .as("a split to apply, and the option to say it was something else entirely")
                    .containsExactly("split", "");
        });
    }

    @Test
    @DisplayName("a confirmed split is not offered as a choice, because there is nothing to choose")
    void evidenceRemovesTheQuestion() {
        SplitCalendar knows = (commodity, from, to) -> SplitCoverage.checked(
                List.of(new com.basis.reconcile.KnownSplit(commodity,
                        LocalDate.of(2020, 8, 31), 4, 1, java.time.Instant.EPOCH)),
                java.time.Instant.EPOCH);

        BreakFinder.Result result = finder(knows).find(
                UploadedStatement.of("fidelity", HISTORY, positions("AAPL,40"), "h.csv", "p.csv", false));

        assertThat(result.breaks()).singleElement()
                .satisfies(record -> assertThat(record.cause().confident()).isTrue());
        assertThat(result.ambiguities())
                .as("basis knows the answer, so inventing a choice would be inventing doubt")
                .isEmpty();
    }

    @Test
    @DisplayName("applying the chosen split clears the break")
    void choosingTheSplitClosesTheLoop() {
        UploadedStatement upload = UploadedStatement.of(
                "fidelity", HISTORY, positions("AAPL,40"), "h.csv", "p.csv", false);
        BreakFinder finder = finder(SplitCalendar.EMPTY);

        Ambiguities.Option chosen = finder.find(upload).ambiguities().get(0).options().get(0);
        UploadedStatement decided = upload.plus(new UploadedStatement.AppliedChoice(
                chosen.kind(), "AAPL", chosen.detail(), LocalDate.of(2020, 8, 31)));

        BreakFinder.Result after = finder.find(decided);
        assertThat(after.breaks()).as("the ledger and the broker now agree").isEmpty();
        assertThat(after.positions()).singleElement().satisfies(holding -> {
            assertThat(holding.quantity().value()).isEqualByComparingTo("40");
            assertThat(holding.costBasis())
                    .as("a split changes the share count and not what the position cost")
                    .isEqualTo("3000.00");
        });
    }

    @Test
    @DisplayName("breaks are ordered so the ones with evidence are read first")
    void confidentBreaksComeFirst() {
        SplitCalendar knowsAaplOnly = (commodity, from, to) -> commodity.symbol().equals("AAPL")
                ? SplitCoverage.checked(List.of(new com.basis.reconcile.KnownSplit(
                        commodity, LocalDate.of(2020, 8, 31), 4, 1, java.time.Instant.EPOCH)),
                        java.time.Instant.EPOCH)
                : SplitCoverage.neverChecked();

        List<String> history = new java.util.ArrayList<>(HISTORY);
        history.add("01/04/2020,Individual,X,YOU BOUGHT MICROSOFT (MSFT) (Cash),MSFT,MICROSOFT,"
                + "Cash,100.00,10,\"\",\"\",\"\",-1000,01/07/2020");

        BreakFinder.Result result = finder(knowsAaplOnly).find(UploadedStatement.of(
                "fidelity", history, positions("AAPL,40", "MSFT,30"), "h.csv", "p.csv", false));

        assertThat(result.breaks()).hasSize(2);
        assertThat(result.breaks().get(0).cause().confident())
                .as("the confirmed one leads, because the first screen is all many people read")
                .isTrue();
        assertThat(result.breaks().get(1).cause().confident()).isFalse();
        assertThat(result.confirmedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a reverse split offered for a shortfall is one the ledger will actually accept")
    void theReverseSplitOptionIsTheRightWayRound() {
        // This was backwards. A broker reporting fewer shares got offered a "3:1 reverse
        // split", which is a split, which ReverseSplit correctly refuses. The refusal became
        // a 500 on the results page, and because the choice had already been saved to the
        // session, every later page load threw the same error. The orientation and the
        // save-after-validating are both fixed; this pins the orientation.
        List<String> history = new java.util.ArrayList<>(HISTORY);
        BreakFinder finder = finder(SplitCalendar.EMPTY);
        UploadedStatement upload = UploadedStatement.of(
                "fidelity", history, positions("AAPL,2"), "h.csv", "p.csv", false);

        Ambiguities.Option reverse = finder.find(upload).ambiguities().get(0).options().get(0);
        assertThat(reverse.kind()).isEqualTo("reverse-split");
        assertThat(reverse.detail())
                .as("new:old, so the new count has to be the smaller number")
                .isEqualTo("1:5");

        UploadedStatement decided = upload.plus(new UploadedStatement.AppliedChoice(
                reverse.kind(), "AAPL", reverse.detail(), LocalDate.of(2021, 1, 4)));
        assertThat(finder.find(decided).breaks())
                .as("the ledger accepted it and the position now agrees")
                .isEmpty();
    }

    private static BreakFinder finder(SplitCalendar splits) {
        return new BreakFinder(splits);
    }

    private static List<String> positions(String... rows) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add("symbol,quantity,cost_basis,kind");
        for (String row : rows) {
            lines.add(row + ",,EQUITY");
        }
        return lines;
    }
}
