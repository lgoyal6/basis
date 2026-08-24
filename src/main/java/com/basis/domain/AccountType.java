package com.basis.domain;

/**
 * The five Beancount account roots. The root fixes the sign convention: a positive
 * posting to an ASSETS or EXPENSES account is a debit, a positive posting to
 * LIABILITIES, EQUITY or INCOME is a credit.
 */
public enum AccountType {
    ASSETS,
    LIABILITIES,
    EQUITY,
    INCOME,
    EXPENSES;

    static AccountType fromRootSegment(String segment) {
        for (AccountType type : values()) {
            if (type.name().equalsIgnoreCase(segment)) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "account root must be one of Assets, Liabilities, Equity, Income, Expenses but was: " + segment);
    }
}
