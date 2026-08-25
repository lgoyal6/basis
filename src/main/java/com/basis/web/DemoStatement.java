package com.basis.web;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * A statement built to produce one of every break the classifier can explain.
 *
 * <p>Somebody who has not uploaded anything yet has no way to judge whether this is worth
 * their brokerage history. The demo is the answer to that, so it has to show the interesting
 * outcomes rather than a clean reconcile: a split it can prove, a ratio it refuses to guess
 * at, a holding it has never heard of, and a cost basis that disagrees.
 *
 * <p>Deliberately designed rather than generated. {@code GeneratedHistory} in the test tree
 * produces random valid histories for the invariant properties, which is the opposite problem:
 * it guarantees the ledger stays consistent and guarantees nothing about which breaks appear.
 * A demo whose contents vary per run is also a demo that can ship with nothing to look at.
 * See docs/ARCHITECTURE.md section 30 for the trade this makes.
 *
 * <p>Every number here is invented. The symbols are real tickers because a demo showing
 * FAKECO teaches nothing about whether split lookup works, and split lookup against a real
 * ticker is the single most convincing thing on the page.
 */
@Component
public class DemoStatement {

    /** Fidelity's real column layout, which is also the format most first uploads arrive in. */
    private static final String HEADER = "Run Date,Account,Account Number,Action,Symbol,"
            + "Description,Type,Price ($),Quantity,Commission ($),Fees ($),Accrued Interest ($),"
            + "Amount ($),Settlement Date";

    public UploadedStatement build() {
        List<String> history = List.of(HEADER,
                row("01/02/2020", "ELECTRONIC FUNDS TRANSFER RECEIVED (Cash)", "", "Funding",
                        "", "", "25000"),
                // Bought before Apple's 2020 split, and the history never records the split.
                // The broker will report four times these shares, and basis can prove why.
                row("01/03/2020", "YOU BOUGHT APPLE INC (AAPL) (Cash)", "AAPL", "APPLE INC",
                        "300.00", "20", "-6000"),
                // A second holding, bought in two lots, so the cost basis has something to
                // disagree about and lot selection has something to choose between.
                row("02/10/2020", "YOU BOUGHT MICROSOFT CORP (MSFT) (Cash)", "MSFT",
                        "MICROSOFT CORP", "180.00", "30", "-5400"),
                row("06/15/2021", "YOU BOUGHT MICROSOFT CORP (MSFT) (Cash)", "MSFT",
                        "MICROSOFT CORP", "260.00", "20", "-5200"),
                // A cash sweep paying out and reinvesting, which is two rows and must not be
                // counted as income twice.
                row("07/31/2021", "DIVIDEND RECEIVED FIDELITY GOVERNMENT MONEY MARKET (SPAXX)",
                        "SPAXX", "FIDELITY GOVERNMENT MONEY MARKET", "", "0", "18.42"),
                row("07/31/2021", "REINVESTMENT FIDELITY GOVERNMENT MONEY MARKET (SPAXX)",
                        "SPAXX", "FIDELITY GOVERNMENT MONEY MARKET", "1.00", "18.42", "-18.42"),
                // Sold part of a holding, so there is a realized gain and a partially
                // consumed lot in the picture.
                row("03/04/2022", "YOU SOLD MICROSOFT CORP (MSFT) (Cash)", "MSFT",
                        "MICROSOFT CORP", "300.00", "-10", "3000"),
                row("01/09/2023", "YOU BOUGHT VANGUARD TOTAL STOCK MKT (VTSAX) (Cash)", "VTSAX",
                        "VANGUARD TOTAL STOCK MKT IDX", "100.00", "12", "-1200"));

        // What the broker says, and every disagreement is deliberate.
        List<String> positions = List.of("symbol,quantity,cost_basis,kind",
                // Four times the shares: a 4 for 1 split this history never applied. With
                // split history available basis confirms it and offers the fix.
                "AAPL,80,,EQUITY",
                // Forty where basis computes forty: this one agrees, which matters. A demo
                // where everything is broken says nothing about false positives.
                "MSFT,40,9800.00,EQUITY",
                // A holding the uploaded history has never mentioned. Not a ratio, not a
                // split, just missing: probably an account transfer that was not exported.
                "TSLA,15,,EQUITY",
                // Fewer shares than basis computed, in a ratio shaped like a reverse split,
                // and nothing confirms one. This is the case basis refuses to guess at.
                "VTSAX,4,,MUTUAL_FUND",
                "SPAXX,18.42,,MUTUAL_FUND");

        return new UploadedStatement("fidelity", UploadedStatement.ACCOUNT, history, positions,
                "demo-history.csv", "demo-positions.csv", true, List.of());
    }

    private static String row(String date, String action, String symbol, String description,
            String price, String quantity, String amount) {
        return String.join(",",
                date, "Individual", "DEMO", quote(action), symbol, quote(description), "Cash",
                price, quote(quantity), "", "", "", amount, date);
    }

    private static String quote(String value) {
        return value.contains(",") ? '"' + value + '"' : value;
    }
}
