package com.basis.ledger.lot;

/**
 * A disposal cannot pick the lots it needs.
 *
 * <p>Concrete rather than abstract, and thrown directly for a malformed selection
 * request such as named quantities that do not add up to the disposal. The two cases a
 * caller has to be able to tell apart get their own subclasses:
 * {@link InsufficientLotsException} when the holding is too small, and
 * {@link UnknownLotException} when the holding is fine but the named lot is not in it.
 */
public class LotSelectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LotSelectionException(String message) {
        super(message);
    }
}
