package com.basis.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every option the help text offers has to be an option the parser accepts.
 *
 * <p>Options that take a following word are listed in an allowlist, so adding one means
 * editing two places. Miss the second and the flag does nothing at all: it parses as a bare
 * boolean, the value is read as a positional argument, and the code falls through to its
 * default without complaint.
 *
 * <p>That is not hypothetical. {@code --currency} was documented in the usage line, read in
 * the command, and defaulted to USD, and because it was missing from the allowlist every
 * foreign currency holding was silently recorded in dollars. Nothing failed. The ledger just
 * held a wrong fact, and a reconciliation then compared two numbers that were not comparable
 * and called the difference a cost basis error.
 *
 * <p>This walks the real help text so it cannot drift: a new documented option fails here
 * until it is wired up.
 */
class DocumentedOptionsTest {

    /**
     * An option followed by a placeholder, which is how the help text spells "takes a value".
     *
     * <p>Matches {@code --cost PRICE} and {@code --on yyyy-mm-dd} but not {@code --with-cash},
     * because a flag with no placeholder after it genuinely does not take one.
     */
    private static final Pattern TAKES_A_VALUE =
            Pattern.compile("--([a-z][a-z-]*) (?=[A-Z]{2,}|yyyy-mm-dd|\")");

    @Test
    @DisplayName("every option the help text shows taking a value is one the parser will bind")
    void documentedOptionsAreParseable() {
        Set<String> documented = new LinkedHashSet<>();
        Matcher matcher = TAKES_A_VALUE.matcher(usageText());
        while (matcher.find()) {
            documented.add(matcher.group(1));
        }

        assertThat(documented)
                .as("the help text should document some options, or this test proves nothing")
                .isNotEmpty();
        assertThat(BasisCli.OPTIONS_WITH_VALUES)
                .as("documented but unparseable options are silently ignored, not rejected")
                .containsAll(documented);
    }

    @Test
    @DisplayName("a space separated option becomes an equals form the argument parser understands")
    void spaceSeparatedOptionsAreNormalised() {
        assertThat(BasisCli.normaliseOptions(new String[] {
                "open", "Assets:Broker:UK", "VOD", "100", "--cost", "90.00", "--currency", "GBP"}))
                .containsExactly("open", "Assets:Broker:UK", "VOD", "100",
                        "--cost=90.00", "--currency=GBP");
    }

    @Test
    @DisplayName("a flag with no value is left alone rather than swallowing the next argument")
    void bareFlagsDoNotConsumeTheNextWord() {
        assertThat(BasisCli.normaliseOptions(new String[] {
                "reconcile", "acct", "p.csv", "--with-cash", "--dry-run"}))
                .as("neither takes a value, so neither should absorb the other")
                .containsExactly("reconcile", "acct", "p.csv", "--with-cash", "--dry-run");
    }

    private static String usageText() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            BasisCli.printUsage(new CliOutput());
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
