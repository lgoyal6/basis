package com.basis.cli;

import java.io.PrintStream;
import org.springframework.stereotype.Component;

/**
 * Where the command line writes.
 *
 * <p>A seam rather than a bare {@code System.out}, so a test can read what a command
 * actually printed. The output is the product for a tool like this: a break nobody can read
 * is a break nobody acts on.
 */
@Component
public class CliOutput {

    private final PrintStream out;
    private final PrintStream err;

    public CliOutput() {
        this(System.out, System.err);
    }

    public CliOutput(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public void line(String text) {
        out.println(text);
    }

    public void blank() {
        out.println();
    }

    public void heading(String text) {
        out.println();
        out.println(text.toUpperCase(java.util.Locale.ROOT));
    }

    public void error(String text) {
        err.println("error: " + text);
    }
}
