package com.basis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * basis independently recomputes brokerage positions from transaction history and
 * reports every disagreement with the broker.
 *
 * <p>No web layer. This is a batch process over an append only ledger, so there is
 * nothing to serve and nothing to listen on.
 */
@SpringBootApplication
public class BasisApplication {

    public static void main(String[] args) {
        SpringApplication.run(BasisApplication.class, args);
    }
}
