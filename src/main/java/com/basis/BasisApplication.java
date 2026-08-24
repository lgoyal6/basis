package com.basis;

import com.basis.cli.BasisCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * basis independently recomputes brokerage positions from transaction history and
 * reports every disagreement with the broker.
 *
 * <p>No web layer. This is a command line process over an append only ledger, so there is
 * nothing to serve and nothing to listen on. See {@link BasisCli} for the commands.
 *
 * <p>Help is answered before the context starts. Every other command needs a database, and
 * making someone stand up Postgres to be told what the commands are would be a poor
 * introduction.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BasisApplication {

    public static void main(String[] args) {
        if (wantsHelp(args)) {
            BasisCli.printUsage(new com.basis.cli.CliOutput());
            System.exit(0);
        }
        System.exit(SpringApplication.exit(SpringApplication.run(BasisApplication.class, args)));
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
