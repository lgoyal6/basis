package com.basis.domain;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A colon-separated account path, for example {@code Assets:Broker:IBKR:AAPL}.
 *
 * <p>The account tree is the only place hierarchy lives. There is no separate broker
 * or portfolio entity in week 1: a broker is a path prefix, which means adding a
 * second broker adds rows and not tables.
 */
public record Account(String name) implements Comparable<Account> {

    public static final String SEPARATOR = ":";

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    public Account {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("account name must not be blank");
        }
        String[] segments = name.split(SEPARATOR, -1);
        if (segments.length < 2) {
            throw new IllegalArgumentException("account must have at least a root and one segment: " + name);
        }
        AccountType.fromRootSegment(segments[0]);
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("illegal account segment '" + segment + "' in: " + name);
            }
        }
    }

    public static Account of(String name) {
        return new Account(name);
    }

    public AccountType type() {
        return AccountType.fromRootSegment(segments().get(0));
    }

    public List<String> segments() {
        return List.of(name.split(SEPARATOR, -1));
    }

    public String leaf() {
        List<String> segments = segments();
        return segments.get(segments.size() - 1);
    }

    public Account child(String segment) {
        return new Account(name + SEPARATOR + segment);
    }

    /** True when this account is {@code other} or sits underneath it. */
    public boolean isUnder(Account other) {
        return name.equals(other.name) || name.startsWith(other.name + SEPARATOR);
    }

    @Override
    public int compareTo(Account other) {
        return name.compareTo(other.name);
    }

    @Override
    public String toString() {
        return name;
    }
}
