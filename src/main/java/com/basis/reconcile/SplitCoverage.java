package com.basis.reconcile;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What the reference data can say about a symbol's splits, including whether it can say
 * anything at all.
 *
 * @param checkedAt when the provider last answered successfully, null when it never has
 * @param detail why a check failed, empty otherwise
 */
public record SplitCoverage(CoverageStatus status, List<KnownSplit> splits, Instant checkedAt, String detail) {

    public SplitCoverage {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(splits, "splits");
        Objects.requireNonNull(detail, "detail");
        splits = List.copyOf(splits);
        if (status != CoverageStatus.CHECKED && !splits.isEmpty()) {
            throw new IllegalArgumentException(status + " coverage cannot carry splits: it never got any");
        }
        if (status == CoverageStatus.CHECKED && checkedAt == null) {
            throw new IllegalArgumentException("checked coverage has to say when it was checked");
        }
    }

    public static SplitCoverage checked(List<KnownSplit> splits, Instant checkedAt) {
        return new SplitCoverage(CoverageStatus.CHECKED, splits, checkedAt, "");
    }

    public static SplitCoverage neverChecked() {
        return new SplitCoverage(CoverageStatus.NEVER_CHECKED, List.of(), null, "");
    }

    public static SplitCoverage checkFailed(String detail, Instant attemptedAt) {
        return new SplitCoverage(CoverageStatus.CHECK_FAILED, List.of(), attemptedAt, detail);
    }

    /** True when an empty split list is genuinely evidence that no split happened. */
    public boolean isAuthoritative() {
        return status == CoverageStatus.CHECKED;
    }

    /** The split matching the ratio, if the data knows of one. */
    public java.util.Optional<KnownSplit> matching(Ratio ratio) {
        return splits.stream().filter(split -> split.matches(ratio)).findFirst();
    }
}
