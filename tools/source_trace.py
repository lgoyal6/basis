"""Build redacted source-finding receipts and an opt-in LangSmith export.
Uses Pydantic validation before calling an explicitly supplied SDK client.
Hashes and counts replace source text; no client or hosted tracing starts here.
"""
from __future__ import annotations
import hashlib
from datetime import datetime, timezone
from typing import Annotated, Literal
from uuid import UUID, uuid4

from pydantic import BaseModel, ConfigDict, Field, model_validator


class SourceTrace(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    schema_version: Literal[1] = 1
    run_id: UUID
    attempt: int = Field(ge=1)
    tool: Literal["basis_source_finding"] = "basis_source_finding"
    model: Literal["lexical", "paraphrase-MiniLM-L3-v2"]
    tenant_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    query_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    source_sha256: list[Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]]
    source_count: int = Field(ge=0)
    context_tokens: int = Field(ge=0)
    abstained: bool

    @model_validator(mode="after")
    def consistent_sources(self):
        if self.source_count != len(self.source_sha256) or self.abstained != (self.source_count == 0):
            raise ValueError("source count and abstention must match source hashes")
        return self


def make_trace(tenant, query, result, *, model="lexical", attempt=1):
    def sha(value): return hashlib.sha256(value.encode()).hexdigest()
    sources = result["sources"]
    return SourceTrace(run_id=uuid4(), attempt=attempt, model=model, tenant_sha256=sha(tenant), query_sha256=sha(query),
                       source_sha256=[sha("\0".join(str(s[k]) for k in ("document_id", "source_version", "page"))) for s in sources],
                       source_count=len(sources), context_tokens=result["context_tokens"], abstained=result["abstained"])


def export_langsmith(client, trace):
    # Revalidation also covers dictionaries crossing a serialization boundary.
    trace = SourceTrace.model_validate(trace)
    now = datetime.now(timezone.utc)
    client.create_run(id=trace.run_id, name=trace.tool, run_type="retriever", project_name="basis-source-finding",
                      inputs={"tenant_sha256": trace.tenant_sha256, "query_sha256": trace.query_sha256},
                      outputs={"source_sha256": trace.source_sha256, "source_count": trace.source_count,
                               "context_tokens": trace.context_tokens, "abstained": trace.abstained},
                      start_time=now, end_time=now,
                      extra={"metadata": {"schema_version": trace.schema_version, "attempt": trace.attempt, "model": trace.model}})
