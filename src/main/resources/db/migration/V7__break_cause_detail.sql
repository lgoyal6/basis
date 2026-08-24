-- break_record was written in week 1, before reconciliation existed, so it carried a
-- single probable_cause TEXT. A cause turned out to have three parts worth keeping
-- apart, and flattening them into one sentence loses all three.
--
-- cause_code groups breaks by the kind of explanation, which is what makes "how often
-- is an unapplied split the answer" a query rather than a text search.
--
-- cause_confident is the difference between "the broker holds four times what we
-- computed, which is the shape of a split" and "there is a 4 for 1 split on record
-- dated 2020-08-31 that this history never applied". Both are worth showing. Only one
-- of them should be acted on without checking.
--
-- suggested_action is what to do about it, and is empty when basis does not know.

-- commodity_class is stored rather than inferred from the symbol on read. Guessing it
-- back would mean asking whether "TRY" is the Turkish lira or a ticker, and a break
-- about cash that reads back as a security is a break nobody can interpret.

ALTER TABLE break_record
    ADD COLUMN cause_code       TEXT    NOT NULL DEFAULT 'UNEXPLAINED',
    ADD COLUMN cause_confident  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN suggested_action TEXT    NOT NULL DEFAULT '',
    ADD COLUMN commodity_class  TEXT    NOT NULL DEFAULT 'OTHER';

COMMENT ON COLUMN break_record.probable_cause IS
    'What basis believes happened, in a sentence a person can check.';
COMMENT ON COLUMN break_record.cause_confident IS
    'True only when something outside the arithmetic corroborates the cause, such as a matching split in reference_data.';

CREATE INDEX break_record_cause_idx ON break_record (cause_code, cause_confident);
