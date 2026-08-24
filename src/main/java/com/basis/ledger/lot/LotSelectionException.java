package com.basis.ledger.lot;

/** Base for every reason a disposal cannot pick the lots it needs. */
public abstract class LotSelectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected LotSelectionException(String message) {
        super(message);
    }
}
