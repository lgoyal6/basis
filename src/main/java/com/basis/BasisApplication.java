package com.basis;

import com.basis.cli.BasisCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * basis independently recomputes brokerage positions from transaction history and
 * reports every disagreement with the broker.
 *
 * <p>Two shapes, one ledger. {@code basis serve} starts a web application; every other
 * command is a process that does one thing and exits with a meaningful code. The web layer
 * computes nothing of its own: it calls the same parser, the same ledger and the same
 * reconciler the CLI calls, and renders what they returned. There is one implementation of
 * the arithmetic and there will only ever be one. See docs/ARCHITECTURE.md section 30.
 *
 * <p>The two modes need different lifecycles, which is why this is not a single call.
 * A command runs, produces an exit code, and the JVM ends. A server starts and stays up,
 * and calling {@code SpringApplication.exit} on it would shut the container down the moment
 * it finished starting.
 *
 * <p>Help is answered before the context starts. Every other command needs a database, and
 * making someone stand up Postgres to be told what the commands are would be a poor
 * introduction.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BasisApplication {

    /** The one command that serves rather than exits. */
    static final String SERVE = "serve";

    public static void main(String[] args) {
        if (wantsHelp(args)) {
            BasisCli.printUsage(new com.basis.cli.CliOutput());
            System.exit(0);
        }
        if (isServe(args)) {
            serve(args);
            return;
        }
        String[] normalised = BasisCli.normaliseOptions(args);
        System.exit(SpringApplication.exit(new SpringApplicationBuilder(BasisApplication.class)
                // Explicit, because the web starter is now on the classpath and a command
                // that quietly opened a port would be a surprising thing for a ledger to do.
                .web(WebApplicationType.NONE)
                .run(normalised)));
    }

    /**
     * Runs until stopped. No exit code, because there is no single outcome to report:
     * a server that has been up for a week has answered a great many questions.
     */
    private static void serve(String[] args) {
        new SpringApplicationBuilder(BasisApplication.class)
                .web(WebApplicationType.SERVLET)
                // Gates the CLI runner off and the controllers on, so neither mode has to
                // check which one it is in.
                .profiles("web")
                .run(BasisCli.normaliseOptions(args));
    }

    private static boolean isServe(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--")) {
                continue;
            }
            return arg.equals(SERVE);
        }
        return false;
    }

    private static boolean wantsHelp(String[] args) {
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h") || arg.equals("help")) {
                return true;
            }
        }
        return false;
    }
}
