package com.basis.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the file, before anything decides what it means.
 *
 * <p>The fixtures here are written to the format as understood, not captured from a real
 * export, and that is the known weakness of this parser. So the tests concentrate on the
 * things that will still matter when the exact column names turn out to be different:
 * junk around the body, commas inside quoted descriptions, columns found by name rather
 * than position, and a refusal to guess when something does not add up.
 */
class FidelityCsvParserTest {

    /** A realistic export: title line, blank, header, rows, then disclaimer prose. */
    private static final List<String> STATEMENT = List.of(
            "Brokerage",
            "",
            "Run Date,Action,Symbol,Description,Type,Quantity,Price ($),Commission ($),Fees ($),Amount ($)",
            "01/15/2026,YOU BOUGHT,AAPL,\"APPLE INC, COM\",Cash,10,150.00,1.00,0.00,-1501.00",
            "02/01/2026,YOU SOLD,AAPL,\"APPLE INC, COM\",Cash,-5,160.00,1.00,0.00,799.00",
            "02/10/2026,DIVIDEND RECEIVED,AAPL,APPLE INC,Cash,,,,,24.00",
            "",
            "\"The data and information in this spreadsheet is provided to you solely for your use\"",
            "\"Brokerage services provided by Fidelity Brokerage Services LLC\"");

    @Test
    @DisplayName("the header is found under the preamble, and the disclaimer below is ignored")
    void findsTheBodyBetweenTheJunk() {
        List<StatementRow> rows = FidelityCsvParser.parse(STATEMENT, "history.csv");

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(StatementRow::action)
                .containsExactly("YOU BOUGHT", "YOU SOLD", "DIVIDEND RECEIVED");
    }

    @Test
    @DisplayName("a comma inside a quoted description does not shift every column after it")
    void quotedFieldsDoNotShiftColumns() {
        StatementRow bought = FidelityCsvParser.parse(STATEMENT, "history.csv").get(0);

        assertThat(bought.description()).isEqualTo("APPLE INC, COM");
        assertThat(bought.symbol()).isEqualTo("AAPL");
        assertThat(bought.quantity()).isEqualByComparingTo("10");
        assertThat(bought.price())
                .as("a naive split on commas would put the description's tail here")
                .isEqualByComparingTo("150.00");
        assertThat(bought.commission()).isEqualByComparingTo("1.00");
        assertThat(bought.amount()).isEqualByComparingTo("-1501.00");
    }

    @Test
    @DisplayName("columns are found by name, so a reordered export still reads correctly")
    void columnsAreFoundByName() {
        List<StatementRow> rows = FidelityCsvParser.parse(List.of(
                "Amount ($),Symbol,Run Date,Action,Quantity,Price ($)",
                "-1501.00,AAPL,01/15/2026,YOU BOUGHT,10,150.00"), "reordered.csv");

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.symbol()).isEqualTo("AAPL");
            assertThat(row.quantity()).isEqualByComparingTo("10");
            assertThat(row.amount()).isEqualByComparingTo("-1501.00");
        });
    }

    @Test
    @DisplayName("a sale's negative quantity is preserved as read, not normalised here")
    void signsAreLeftForTheMapper() {
        StatementRow sold = FidelityCsvParser.parse(STATEMENT, "history.csv").get(1);

        assertThat(sold.quantity())
                .as("the parser reports the page, the mapper decides what it means")
                .isEqualByComparingTo("-5");
    }

    @Test
    @DisplayName("an empty cell is absent, not zero")
    void emptyCellsAreNull() {
        StatementRow dividend = FidelityCsvParser.parse(STATEMENT, "history.csv").get(2);

        assertThat(dividend.quantity())
                .as("a dividend has no share count, and calling it zero would be a claim")
                .isNull();
        assertThat(dividend.price()).isNull();
        assertThat(dividend.amount()).isEqualByComparingTo("24.00");
    }

    @Test
    @DisplayName("dollar signs, thousands separators and parentheses are all tolerated")
    void tolerantAboutNumberDecoration() {
        List<StatementRow> rows = FidelityCsvParser.parse(List.of(
                "Run Date,Action,Symbol,Quantity,Price ($),Amount ($)",
                "01/15/2026,YOU BOUGHT,AAPL,\"1,200\",\"$1,050.25\",\"($1,260,300.00)\""), "decorated.csv");

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.quantity()).isEqualByComparingTo("1200");
            assertThat(row.price()).isEqualByComparingTo("1050.25");
            assertThat(row.amount())
                    .as("parentheses mean negative in a spreadsheet export")
                    .isEqualByComparingTo("-1260300.00");
        });
    }

    @Test
    @DisplayName("an as of date in the same cell is read as the transaction date")
    void handlesAsOfDates() {
        List<StatementRow> rows = FidelityCsvParser.parse(List.of(
                "Run Date,Action,Symbol,Quantity,Amount ($)",
                "01/15/2026 as of 01/13/2026,YOU BOUGHT,AAPL,10,-1500.00"), "asof.csv");

        assertThat(rows).singleElement()
                .satisfies(row -> assertThat(row.date()).isEqualTo(LocalDate.of(2026, 1, 15)));
    }

    @Test
    @DisplayName("every line is kept verbatim, because that is the replay story")
    void keepsTheRawLine() {
        StatementRow bought = FidelityCsvParser.parse(STATEMENT, "history.csv").get(0);

        assertThat(bought.raw())
                .as("txn.source_row holds this forever so a parser bug is fixable by replay")
                .isEqualTo(STATEMENT.get(3));
    }

    @Test
    @DisplayName("rows are numbered from one, since Fidelity gives no row identifier")
    void rowsAreNumbered() {
        assertThat(FidelityCsvParser.parse(STATEMENT, "history.csv"))
                .extracting(StatementRow::ordinal)
                .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("a file that is not a Fidelity export says so instead of producing nonsense")
    void refusesAFileWithNoRecognisableHeader() {
        assertThatThrownBy(() -> FidelityCsvParser.parse(List.of(
                "date,ticker,shares", "2026-01-15,AAPL,10"), "mystery.csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not look like a Fidelity export");
    }

    @Test
    @DisplayName("a header with no amount column names the missing column and where to fix it")
    void refusesAHeaderMissingARequiredColumn() {
        assertThatThrownBy(() -> FidelityCsvParser.parse(List.of(
                "Run Date,Action,Symbol,Quantity", "01/15/2026,YOU BOUGHT,AAPL,10"), "thin.csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no amount column")
                .hasMessageContaining("COLUMN_ALIASES");
    }

    @Test
    @DisplayName("a header with no transactions under it is refused, not imported as nothing")
    void refusesAnEmptyBody() {
        assertThatThrownBy(() -> FidelityCsvParser.parse(List.of(
                "Run Date,Action,Symbol,Quantity,Amount ($)",
                "",
                "\"Disclaimer text\""), "empty.csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no transactions");
    }

    @Test
    @DisplayName("a number that is not a number names the row and the field")
    void refusesAnUnparseableNumber() {
        assertThatThrownBy(() -> FidelityCsvParser.parse(List.of(
                "Run Date,Action,Symbol,Quantity,Amount ($)",
                "01/15/2026,YOU BOUGHT,AAPL,ten,-1500.00"), "typo.csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typo.csv:2")
                .hasMessageContaining("quantity is not a number: 'ten'");
    }

    @Test
    @DisplayName("splitting handles doubled quotes inside a quoted field")
    void handlesEscapedQuotes() {
        assertThat(CsvLine.split("a,\"say \"\"hi\"\" now\",c"))
                .containsExactly("a", "say \"hi\" now", "c");
    }
}
