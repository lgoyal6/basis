package com.basis.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * What the web layer needs that the CLI does not.
 *
 * <p>Scoped to the web profile so a command line invocation neither schedules anything nor
 * builds anything it will not use.
 */
@Configuration
@Profile("web")
@EnableScheduling
public class WebConfig {

    /**
     * Limits on what a stranger can send.
     *
     * @param maxBytes largest upload accepted, before parsing
     * @param maxRows most statement rows read from one file
     * @param sessionMinutes how long an upload survives, if nobody deletes it sooner
     */
    @ConfigurationProperties(prefix = "basis.web")
    public record Limits(int maxBytes, int maxRows, int sessionMinutes) {

        public Limits {
            maxBytes = maxBytes <= 0 ? 5 * 1024 * 1024 : maxBytes;
            maxRows = maxRows <= 0 ? 50_000 : maxRows;
            sessionMinutes = sessionMinutes <= 0 ? 120 : sessionMinutes;
        }

        public String maxDescription() {
            return maxBytes / (1024 * 1024) + " MB";
        }
    }

    /**
     * Sweeps expired uploads.
     *
     * <p>Expiry is already enforced on read, so this is not what makes the promise true. It
     * is what stops an abandoned session from occupying memory until the process restarts,
     * which matters because the alternative to deleting data on a timer is keeping it.
     */
    @Bean
    Sweeper sweeper(SessionStore sessions) {
        return new Sweeper(sessions);
    }

    public static class Sweeper {

        private final SessionStore sessions;

        Sweeper(SessionStore sessions) {
            this.sessions = sessions;
        }

        @Scheduled(fixedDelay = 5 * 60 * 1000L)
        public void sweep() {
            sessions.sweep();
        }
    }
}
