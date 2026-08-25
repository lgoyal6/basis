package com.basis.importer;

import java.util.List;
import java.util.Objects;

/**
 * What one import did.
 *
 * @param alreadyPresent rows whose transactions were already in the ledger. Not an error:
 *     statements overlap, and re-importing last month's file alongside this month's is the
 *     normal way to use this.
 */
public record ImportReport(
        long batchId,
        String source,
        int rowsRead,
        int eventsRecorded,
        int alreadyPresent,
        List<String> notes) {

    public ImportReport {
        Objects.requireNonNull(source, "source");
        notes = List.copyOf(notes);
    }

    public boolean changedAnything() {
        return eventsRecorded > 0;
    }

    @Override
    public String toString() {
        return rowsRead + " row(s) read, " + eventsRecorded + " transaction(s) recorded, "
                + alreadyPresent + " already present";
    }
}
