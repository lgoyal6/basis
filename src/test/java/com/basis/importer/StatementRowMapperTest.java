package com.basis.importer;

import static com.basis.support.Fixtures.IBKR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.Account;
import com.basis.domain.event.Buy;
import com.basis.domain.event.CashDividend;
import com.basis.domain.event.Fee;
import com.basis.domain.event.LedgerEvent;
import com.basis.domain.event.Sell;
import com.basis.domain.event.Transfer;
import com.basis.ledger.LedgerAccounts;
import com.basis.reference.SymbolChange;
import com.basis.reference.CommodityCatalog;
import com.basis.reference.SymbolMapping;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deciding what a row means.
 *
 * <p>The action words here are the part most likely to be wrong against a real export, so
 * the behaviour that matters most is the last test: an action nobody taught it stops the
 * import and says what it saw, rather than dropping a transaction that resurfaces weeks
 * later as a break with a confident wrong explanation on it.
 */
class StatementRowMapperTest {

    private static final Account EXTERNAL = Account.of("Assets:Bank:External");
    private static final LocalDate DATE = LocalDate.of(2026, 1, 15);

    private static final BrokerProfile FIDELITY = BrokerProfiles.load("fidelity");

    private final StatementRowMapper mapper =
            new StatementRowMapper(FIDELITY, IBKR, EXTERNAL, SymbolMapping.empty(),
                    CommodityCatalog.empty(), "history.csv");

    @Test
    @DisplayName("YOU BOUGHT becomes a purchase, with commission and fees charged together")
    void mapsAPurchase() {
        LedgerEvent event = one(row("YOU BOUGHT", "AAPL", "10", "150.00", "1.00", "0.50", "-1501.50"));

        assertThat(event).isInstanceOfSatisfying(Buy.class, buy -> {
            assertThat(buy.commodity().symbol()).isEqualTo("AAPL");
            assertThat(buy.quantity().value()).isEqualByComparingTo("10");
            assertThat(buy.price().value()).isEqualByComparingTo("150.00");
            assertThat(buy.commission().toMajorUnits())
                    .as("commission and fees are one charge to the ledger")
                    .isEqualByComparingTo("1.50");
            assertThat(buy.date()).isEqualTo(DATE);
        });
    }

    @Test
    @DisplayName("a sale's negative quantity is normalised, since the event states it positive")
    void normalisesTheSignOfASale() {
        LedgerEvent event = one(row("YOU SOLD", "AAPL", "-5", "160.00", "1.00", "0.00", "799.00"));

        assertThat(event).isInstanceOfSatisfying(Sell.class, sell ->
                assertThat(sell.quantity().value())
                        .as("one sign convention in the ledger, not two")
                        .isEqualByComparingTo("5"));
    }

    @Test
    @DisplayName("a dividend becomes income, with its amount taken as gross")
    void mapsADividend() {
        LedgerEvent event = one(row("DIVIDEND RECEIVED", "AAPL", "", "", "", "", "24.00"));

        assertThat(event).isInstanceOfSatisfying(CashDividend.class, dividend -> {
            assertThat(dividend.grossAmount().toMajorUnits()).isEqualByComparingTo("24.00");
            assertThat(dividend.withheldAmount().isZero())
                    .as("Fidelity reports withholding on its own line, not netted into this one")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("under Fidelity a reinvestment is only the purchase, since the dividend is its own row")
    void fidelityReinvestmentIsJustAPurchase() {
        List<LedgerEvent> events =
                mapper.toEvents(row("REINVESTMENT", "AAPL", "2", "150.00", "", "", "-300.00"));

        assertThat(events)
                .as("a real export reports the distribution separately, and counting it here"
                        + " would book the income twice")
                .singleElement()
                .isInstanceOfSatisfying(Buy.class, buy -> {
                    assertThat(buy.quantity().value()).isEqualByComparingTo("2");
                    assertThat(buy.price().value()).isEqualByComparingTo("150.00");
                });
    }

    @Test
    @DisplayName("where a broker reports only the reinvestment, it is a distribution and a purchase")
    void reinvestmentKindIsADistributionThenAPurchase() {
        StatementRowMapper schwab = new StatementRowMapper(BrokerProfiles.load("schwab"), IBKR,
                EXTERNAL, SymbolMapping.empty(), CommodityCatalog.empty(), "schwab.csv");

        List<LedgerEvent> events =
                schwab.toEvents(row("Reinvest Shares", "AAPL", "2", "150.00", "", "", "-300.00"));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOfSatisfying(CashDividend.class, dividend ->
                assertThat(dividend.grossAmount().toMajorUnits()).isEqualByComparingTo("300.00"));
        assertThat(events.get(1)).isInstanceOf(Buy.class);
        assertThat(events.get(0).idempotencyKey())
                .as("two events from one row need different keys or the second is dropped")
                .isNotEqualTo(events.get(1).idempotencyKey());
    }

    @Test
    @DisplayName("a fee is charged to commissions, withholding to taxes")
    void chargesGoToTheRightAccount() {
        assertThat(one(row("SERVICE FEE", "", "", "", "", "", "-7.50")))
                .isInstanceOfSatisfying(Fee.class, fee -> {
                    assertThat(fee.expenseAccount()).isEqualTo(LedgerAccounts.COMMISSIONS);
                    assertThat(fee.amount().toMajorUnits()).isEqualByComparingTo("7.50");
                });

        assertThat(one(row("FOREIGN TAX PAID", "AAPL", "", "", "", "", "-3.60")))
                .isInstanceOfSatisfying(Fee.class, fee ->
                        assertThat(fee.expenseAccount()).isEqualTo(LedgerAccounts.WITHHOLDING_TAX));
    }

    @Test
    @DisplayName("cash in and cash out are the same event pointing opposite ways")
    void transferDirectionComesFromTheSign() {
        assertThat(one(row("ELECTRONIC FUNDS TRANSFER RECEIVED", "", "", "", "", "", "5000.00")))
                .isInstanceOfSatisfying(Transfer.class, transfer -> {
                    assertThat(transfer.fromAccount()).isEqualTo(EXTERNAL);
                    assertThat(transfer.toAccount()).isEqualTo(IBKR);
                });

        assertThat(one(row("DIRECT DEBIT", "", "", "", "", "", "-250.00")))
                .isInstanceOfSatisfying(Transfer.class, transfer -> {
                    assertThat(transfer.fromAccount()).isEqualTo(IBKR);
                    assertThat(transfer.toAccount()).isEqualTo(EXTERNAL);
                });
    }

    @Test
    @DisplayName("a price is worked out from the amount when the column is blank")
    void derivesAMissingPrice() {
        LedgerEvent event = one(row("YOU BOUGHT", "AAPL", "4", "", "", "", "-600.00"));

        assertThat(event).isInstanceOfSatisfying(Buy.class, buy ->
                assertThat(buy.price().value())
                        .as("losing a real transaction over a blank cell would be worse")
                        .isEqualByComparingTo("150.00"));
    }

    @Test
    @DisplayName("a renamed ticker is resolved at import, so the ledger has one position not two")
    void appliesRenamesAtImport() {
        StatementRowMapper renaming = new StatementRowMapper(FIDELITY, IBKR, EXTERNAL,
                SymbolMapping.of(List.of(new SymbolChange("FB", "META", LocalDate.of(2022, 6, 9), ""))),
                CommodityCatalog.empty(), "history.csv");

        LedgerEvent event = renaming.toEvents(
                row("YOU BOUGHT", "FB", "10", "200.00", "0.00", "0.00", "-2000.00")).get(0);

        assertThat(((Buy) event).commodity().symbol())
                .as("a 2021 statement says FB, and today's holding is META")
                .isEqualTo("META");
    }

    @Test
    @DisplayName("two rows that look identical get different keys, from their position in the file")
    void identicalRowsStayDistinct() {
        StatementRow first = row("YOU BOUGHT", "AAPL", "10", "150.00", "0.00", "0.00", "-1500.00");
        StatementRow second = new StatementRow(2, first.date(), first.action(), first.symbol(),
                first.description(), first.quantity(), first.price(), first.commission(), first.fees(),
                first.amount(), first.raw());

        assertThat(one(first).idempotencyKey())
                .as("Fidelity gives no row id, so the row number is what tells two fills apart")
                .isNotEqualTo(one(second).idempotencyKey());
    }

    @Test
    @DisplayName("an unrecognised action stops the import and says exactly what it saw")
    void refusesAnUnknownAction() {
        assertThatThrownBy(() -> mapper.toEvents(
                row("BOND INTEREST ACCRUAL", "", "", "", "", "", "12.34")))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("history.csv row 1")
                .hasMessageContaining("'BOND INTEREST ACCRUAL' is not recognised")
                .hasMessageContaining("Nothing has been imported")
                .hasMessageContaining("config/brokers")
                .hasMessageContaining("YOU BOUGHT");
    }

    @Test
    @DisplayName("a trade with no symbol is refused rather than booked against nothing")
    void refusesATradeWithNoSymbol() {
        assertThatThrownBy(() -> mapper.toEvents(
                row("YOU BOUGHT", "", "10", "150.00", "", "", "-1500.00")))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("Symbol column is empty");
    }

    @Test
    @DisplayName("the longer, more specific action phrase wins")
    void longestPrefixWins() {
        assertThat(FIDELITY.classify("DIVIDEND REINVEST AAPL"))
                .as("the longer phrase wins, and for Fidelity it means a purchase")
                .contains(ActionKind.BUY);
        assertThat(FIDELITY.classify("DIVIDEND RECEIVED AAPL")).contains(ActionKind.CASH_DIVIDEND);
    }

    private LedgerEvent one(StatementRow row) {
        List<LedgerEvent> events = mapper.toEvents(row);
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private static StatementRow row(String action, String symbol, String quantity, String price,
            String commission, String fees, String amount) {
        return new StatementRow(1, DATE, action, symbol, "",
                decimal(quantity), decimal(price), decimal(commission), decimal(fees), decimal(amount),
                String.join(",", "01/15/2026", action, symbol, quantity, price, amount));
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isEmpty() ? null : new BigDecimal(value);
    }
}
