package com.basis.documents.application;

import com.basis.documents.domain.ExtractedField;
import java.io.InputStream;
import java.util.List;

public interface DocumentExtractor {
    boolean supports(String mediaType, String filename);
    List<ExtractedField> extract(InputStream input, String sourceVersion);
}
