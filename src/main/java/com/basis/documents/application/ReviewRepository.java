package com.basis.documents.application;

import com.basis.documents.domain.NormalizedFact;
import com.basis.documents.domain.ReviewTask;

public interface ReviewRepository {
    ReviewTask findTask(String tenantId, String taskId);
    NormalizedFact findFact(String tenantId, String factId);
    void appendDecision(String tenantId, ReviewWorkflow.Decision decision);
}
