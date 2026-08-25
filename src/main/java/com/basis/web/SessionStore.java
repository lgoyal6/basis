package com.basis.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Where an uploaded statement lives while somebody looks at their breaks.
 *
 * <p>In memory, and nowhere else. Not in Postgres, not on disk, not in a log. That is the
 * point rather than a shortcut: the upload page promises that the data is deleted, and a
 * promise backed by a delete statement is only as good as the backups, the replicas and the
 * write ahead log. A promise backed by "it was never written down" is checkable by reading
 * this file.
 *
 * <p>The cost is real and worth stating. A redeploy loses every open session, and a second
 * instance would not see the first one's uploads. Both are acceptable for a flow whose whole
 * shape is upload, look, leave, and neither is worth trading the privacy claim for. If this
 * ever needs to survive a restart, that is a decision to take deliberately and to write down,
 * not a refactor.
 *
 * <p>Entries expire on a timer and can be deleted immediately. Expiry is checked on read as
 * well as swept in the background, so an expired session is unreachable the instant it
 * expires rather than whenever the sweeper next runs.
 */
@Component
public class SessionStore {

    /** Long enough to read a break list and act on it, short enough not to be storage. */
    public static final Duration LIFETIME = Duration.ofHours(2);

    /**
     * A ceiling, so a public endpoint cannot be turned into a memory exhaustion attack by
     * uploading many files. The oldest session is evicted when it is reached.
     */
    private static final int MAX_SESSIONS = 500;

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public SessionStore(Clock clock) {
        this.clock = clock;
    }

    private record Entry(UploadedStatement statement, Instant expiresAt) {
    }

    /**
     * Stores a statement and returns the opaque id that reaches it.
     *
     * <p>256 bits from {@link SecureRandom}, so the id cannot be guessed and carries no
     * information: not a counter, not a hash of the file, not a timestamp. Somebody else's
     * session is unreachable because the id is unguessable, which is the only protection a
     * flow with no accounts can offer, and it has to actually hold.
     */
    public String put(UploadedStatement statement) {
        evictIfFull();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(id, new Entry(statement, clock.instant().plus(LIFETIME)));
        return id;
    }

    /** Replaces a session's contents in place, for a choice the user made about it. */
    public void replace(String id, UploadedStatement statement) {
        sessions.computeIfPresent(id, (key, existing) -> new Entry(statement, existing.expiresAt()));
    }

    public Optional<UploadedStatement> get(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Entry entry = sessions.get(id);
        if (entry == null) {
            return Optional.empty();
        }
        if (clock.instant().isAfter(entry.expiresAt())) {
            // Gone the moment it expires, not the next time a sweeper happens to run.
            sessions.remove(id);
            return Optional.empty();
        }
        return Optional.of(entry.statement());
    }

    /** What the delete button calls. Immediate, and returns whether there was anything there. */
    public boolean delete(String id) {
        return id != null && sessions.remove(id) != null;
    }

    public Optional<Instant> expiryOf(String id) {
        Entry entry = id == null ? null : sessions.get(id);
        return Optional.ofNullable(entry).map(Entry::expiresAt);
    }

    /** Removes everything already expired. Called on a schedule; also safe to call anytime. */
    public int sweep() {
        Instant now = clock.instant();
        int before = sessions.size();
        sessions.values().removeIf(entry -> now.isAfter(entry.expiresAt()));
        return before - sessions.size();
    }

    public int size() {
        return sessions.size();
    }

    private void evictIfFull() {
        if (sessions.size() < MAX_SESSIONS) {
            return;
        }
        sweep();
        while (sessions.size() >= MAX_SESSIONS) {
            sessions.entrySet().stream()
                    .min(Map.Entry.comparingByValue(
                            java.util.Comparator.comparing(Entry::expiresAt)))
                    .map(Map.Entry::getKey)
                    .ifPresent(sessions::remove);
        }
    }
}
