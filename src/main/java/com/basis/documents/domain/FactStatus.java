package com.basis.documents.domain;

/** Lifecycle for an extracted financial fact. Values are immutable; corrections create rows. */
public enum FactStatus {
    NORMALIZED, VERIFIED, NEEDS_REVIEW, APPROVED, REJECTED, SUPERSEDED, MERGED,
    SOURCE_UNUSABLE, SOURCE_DELETED;

    public boolean canTransitionTo(FactStatus next) {
        if (next == null || next == this) return false;
        return switch (this) {
            case NORMALIZED -> next == VERIFIED || next == NEEDS_REVIEW || next == SOURCE_UNUSABLE || next == SOURCE_DELETED;
            case VERIFIED -> next == APPROVED || next == NEEDS_REVIEW || next == REJECTED || next == SOURCE_UNUSABLE || next == SOURCE_DELETED;
            case NEEDS_REVIEW -> next == APPROVED || next == REJECTED || next == SUPERSEDED || next == MERGED || next == SOURCE_UNUSABLE || next == SOURCE_DELETED;
            case APPROVED -> next == SUPERSEDED || next == MERGED || next == SOURCE_UNUSABLE || next == SOURCE_DELETED;
            case REJECTED, SUPERSEDED, MERGED, SOURCE_UNUSABLE, SOURCE_DELETED -> false;
        };
    }
}
