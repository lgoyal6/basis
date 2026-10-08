package com.basis.documents.application;

import java.io.InputStream;

/** Immutable original-document storage boundary. Implementations must be content addressed. */
public interface DocumentObjectStore {
    Stored put(String tenantId, String sha256, InputStream input, long size);
    InputStream get(String tenantId, String sha256);
    record Stored(String key, long size) { }
}
