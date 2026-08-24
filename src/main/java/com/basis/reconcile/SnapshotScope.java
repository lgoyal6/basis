package com.basis.reconcile;

/**
 * What a broker snapshot claims to cover.
 *
 * <p>Absence has to mean something definite, and it cannot mean the same thing for every
 * statement. A position report that lists securities and omits a holding is saying the
 * holding is gone, which is a break worth raising. The same report omitting the cash line
 * is usually saying nothing at all about cash, because plenty of position reports simply
 * do not carry it.
 *
 * <p>Rather than guess which kind of silence it is looking at, the reconciler is told.
 * The parser knows what was on the page; the reconciler does not.
 */
public enum SnapshotScope {
    /** Securities only. Cash is not compared, in either direction. */
    SECURITIES_ONLY,
    /** Securities and cash. An omitted cash line means the balance is zero. */
    SECURITIES_AND_CASH;

    public boolean covers(com.basis.domain.Commodity commodity) {
        return !commodity.isCash() || this == SECURITIES_AND_CASH;
    }
}
