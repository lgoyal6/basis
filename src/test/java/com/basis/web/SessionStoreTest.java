package com.basis.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The store the privacy page makes claims about.
 *
 * <p>Each of these corresponds to a sentence somebody is being asked to believe before they
 * upload their trading history. If one of them stops holding, the page is lying.
 */
class SessionStoreTest {

    /** Time under test control, because expiry is the whole subject. */
    private static final class Ticking extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    private final Ticking clock = new Ticking();
    private final SessionStore store = new SessionStore(clock);

    @Test
    @DisplayName("an upload is reachable by its id and by nothing else")
    void storedThenFound() {
        String id = store.put(statement("a"));

        assertThat(store.get(id)).isPresent();
        assertThat(store.get("not-an-id")).isEmpty();
        assertThat(store.get(null)).isEmpty();
        assertThat(store.get("")).isEmpty();
    }

    @Test
    @DisplayName("ids are unguessable and carry nothing about the file")
    void idsAreOpaque() {
        String first = store.put(statement("same"));
        String second = store.put(statement("same"));

        assertThat(first).isNotEqualTo(second)
                .as("not a hash of the contents, or identical files would collide");
        assertThat(first.length()).as("256 bits, url safe base64").isGreaterThanOrEqualTo(43);
        assertThat(first).doesNotContain("same");
    }

    @Test
    @DisplayName("it becomes unreachable the moment it expires, not when a sweeper next runs")
    void expiryIsEnforcedOnRead() {
        String id = store.put(statement("a"));

        clock.advance(SessionStore.LIFETIME.minusSeconds(1));
        assertThat(store.get(id)).as("still inside its lifetime").isPresent();

        clock.advance(Duration.ofSeconds(2));
        assertThat(store.get(id))
                .as("no sweep has run, and it is already gone")
                .isEmpty();
        assertThat(store.size()).as("and reading it removed it rather than leaving it there").isZero();
    }

    @Test
    @DisplayName("delete is immediate, and says whether there was anything to delete")
    void deleteWorksNow() {
        String id = store.put(statement("a"));

        assertThat(store.delete(id)).isTrue();
        assertThat(store.get(id)).isEmpty();
        assertThat(store.delete(id)).as("already gone").isFalse();
        assertThat(store.delete(null)).isFalse();
    }

    @Test
    @DisplayName("the store is capped, so a public endpoint cannot be filled up")
    void itIsBounded() {
        for (int i = 0; i < 700; i++) {
            store.put(statement("file-" + i));
        }

        assertThat(store.size())
                .as("an unbounded map behind an upload form is a memory exhaustion attack")
                .isLessThanOrEqualTo(500);
    }

    @Test
    @DisplayName("a replaced session keeps its original expiry, so choosing does not extend the clock")
    void replacingDoesNotResetTheTimer() {
        String id = store.put(statement("a"));
        Instant original = store.expiryOf(id).orElseThrow();

        clock.advance(Duration.ofMinutes(30));
        store.replace(id, statement("a-with-a-choice"));

        assertThat(store.expiryOf(id))
                .as("otherwise clicking through choices could keep data alive indefinitely")
                .contains(original);
    }

    @Test
    @DisplayName("sweeping removes what has expired and leaves what has not")
    void sweepRemovesOnlyTheExpired() {
        String old = store.put(statement("old"));
        clock.advance(SessionStore.LIFETIME.minusMinutes(1));
        String fresh = store.put(statement("fresh"));
        clock.advance(Duration.ofMinutes(2));

        assertThat(store.sweep()).isEqualTo(1);
        assertThat(store.get(old)).isEmpty();
        assertThat(store.get(fresh)).isPresent();
    }

    private static UploadedStatement statement(String marker) {
        return UploadedStatement.of("fidelity", List.of("header", marker), List.of(),
                "h.csv", null, false);
    }
}
