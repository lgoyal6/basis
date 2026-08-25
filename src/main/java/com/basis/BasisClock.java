package com.basis;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One clock, for both shapes of the process.
 *
 * <p>Not inside the web configuration, which is where it started. Anything that needs to know
 * today's date needs it whether it is serving a page or running a command, and scoping it to
 * the web profile meant a CLI invocation could not construct the services that take one.
 *
 * <p>Injected rather than called statically so tests can move time without waiting for it.
 */
@Configuration
public class BasisClock {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Imports run to completion. The fault injection harness replaces this bean to stop one
     * part way through and measure what recovery does about it.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    com.basis.importer.ImportInterruption importInterruption() {
        return com.basis.importer.ImportInterruption.NONE;
    }
}
