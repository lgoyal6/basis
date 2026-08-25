package com.basis.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.Account;
import com.basis.domain.event.LedgerEvent;
import com.basis.reference.CommodityCatalog;
import com.basis.reference.SymbolMapping;
import com.basis.cli.BasisCli;
import com.basis.support.Fixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What the importer tells someone when it refuses a row.
 *
 * <p>The generic answer is to add the phrase to the broker profile, which is right for almost
 * everything and is the worst possible advice for a corporate action. Those are left out of
 * every profile on purpose, because the row records that the event happened and never on what
 * terms. A tool that answers a merger by telling you to add the phrase is telling you to
 * silently restate a cost basis nobody stated, and every later sale of that holding would
 * compute its gain from it.
 *
 * <p>Pinned here because an error message is exactly the kind of thing that stops being true
 * without anybody noticing. Nothing else in the suite reads what these say.
 */
class UnrecognisedActionAdviceTest {

    private static final String HEADER = "Run Date,Account,Account Number,Action,Symbol,"
            + "Description,Type,Price ($),Quantity,Commission ($),Fees ($),Accrued Interest ($),"
            + "Amount ($),Settlement Date";

    private final BrokerProfile fidelity = BrokerProfiles.load("fidelity");

    @ParameterizedTest
    @CsvSource({
        "SPLIT SOME CORP (ABC), basis apply split",
        "REVERSE SPLIT SOME CORP (ABC), basis apply reverse-split",
        "SPIN OFF SOME CORP (ABC), basis apply spin-off",
        "STOCK DIVIDEND SOME CORP (ABC), basis apply stock-dividend",
        "CASH IN LIEU OF FRACTIONAL SHARE (ABC), basis apply reverse-split",
        "NAME CHANGE SOME CORP (ABC), config/symbol-changes.csv",
    })
    @DisplayName("stopping on a corporate action names the command that handles it properly")
    void theStopSaysWhatToDoInstead(String action, String expectedRemedy) {
        assertThatThrownBy(() -> map(action))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageContaining(expectedRemedy)
                .as("the generic advice is the harmful one here and must not appear")
                .hasMessageNotContaining("add the phrase to the");
    }

    @Test
    @DisplayName("every command the advice names is a command the CLI actually dispatches")
    void theAdviceNeverNamesACommandThatDoesNotExist() {
        // The reason this test exists. The first version of this advice told people to run
        // "basis apply cash-in-lieu", which has never been a command: cash in lieu is a flag
        // on apply reverse-split. The test that covered it asserted the message SAID that,
        // not that what it said was true, so it passed while sending people to a usage error.
        Pattern named = Pattern.compile("basis apply ([a-z-]+)");
        List<String> namedCommands = new ArrayList<>();
        for (String advice : StatementRowMapper.CORPORATE_ACTIONS.values()) {
            Matcher matcher = named.matcher(advice);
            while (matcher.find()) {
                namedCommands.add(matcher.group(1));
            }
        }

        assertThat(namedCommands).as("the advice should name some commands, or this proves nothing")
                .isNotEmpty();
        assertThat(namedCommands)
                .as("every one of these has to be something 'basis apply' will accept")
                .isSubsetOf(BasisCli.APPLY_ACTIONS);
    }

    @Test
    @DisplayName("cash in lieu is offered as the flag it is, not as a command it is not")
    void cashInLieuIsOfferedAsAFlag() {
        assertThatThrownBy(() -> map("CASH IN LIEU OF FRACTIONAL SHARE (ABC)"))
                .hasMessageContaining("--cash-in-lieu")
                .hasMessageContaining("apply reverse-split");
    }

    @Test
    @DisplayName("a rename is told apart from the corporate actions that do restate a basis")
    void aRenameGetsItsOwnAnswer() {
        assertThatThrownBy(() -> map("NAME CHANGE SOME CORP (ABC)"))
                .as("nothing about a rename restates a cost basis, so it should not say so")
                .hasMessageNotContaining("cost basis")
                .hasMessageContaining("moves no value");
    }

    @Test
    @DisplayName("an ordinary unknown row still gets the profile advice and the list of phrases")
    void anOrdinaryUnknownRowStillPointsAtTheProfile() {
        assertThatThrownBy(() -> map("SOME ROW NOBODY HAS EVER SEEN"))
                .hasMessageContaining("add the phrase to the Fidelity profile")
                .hasMessageContaining("YOU BOUGHT");
    }

    /** Puts one action through the whole importer, which is where the advice is produced. */
    private java.util.List<LedgerEvent> map(String action) {
        StatementRow row = new StatementParser(fidelity).parse(java.util.List.of(HEADER,
                "10/01/2026,Individual,Z1," + action + ",ABC,SOME CORP,Cash,\"\",\"\",\"\","
                        + "\"\",\"\",0,10/01/2026"), "real.csv").get(0);
        return new StatementRowMapper(fidelity, Fixtures.IBKR, Account.of("Assets:Bank:External"),
                SymbolMapping.empty(), CommodityCatalog.empty(), "real.csv").toEvents(row);
    }
}
