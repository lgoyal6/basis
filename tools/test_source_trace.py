"""Check redacted trace validation and actual LangSmith SDK serialization.
Uses source_trace with an in-process offline HTTP transport.
Invalid records must not reach the client; no hosted trace is written.
"""
import json
import unittest
from unittest.mock import Mock

import requests
from langsmith import Client
from pydantic import ValidationError
from source_trace import export_langsmith, make_trace


class OfflineSession(requests.Session):
    def __init__(self):
        super().__init__(); self.requests = []
    def send(self, request, **kwargs):
        self.requests.append(request)
        response = requests.Response(); response.status_code = 200; response._content = b'{}'; response.request = request
        return response


class SourceTraceTest(unittest.TestCase):
    def trace(self):
        return make_trace("private-tenant", "Bearer SECRET query", {"sources": [{"document_id": "private-doc", "source_version": "sensitive-version", "page": 3, "text": "confidential source"}], "context_tokens": 9, "abstained": False})

    def test_actual_sdk_serializes_redacted_receipt_with_offline_transport(self):
        session = OfflineSession()
        client = Client(api_url="https://offline.invalid", api_key="offline-test", session=session, auto_batch_tracing=False,
                        omit_traced_runtime_info=True, info={}, tracing_sampling_rate=1, tracing_mode="langsmith")
        try:
            trace = self.trace(); export_langsmith(client, trace)
        finally:
            client.close()
        posted = [r for r in session.requests if r.method == "POST" and r.url.endswith("/runs")]
        self.assertEqual(len(posted), 1)
        body = posted[0].body
        if isinstance(body, bytes): body = body.decode()
        data = json.loads(body)
        self.assertEqual(data["id"], str(trace.run_id))
        self.assertEqual(data["extra"]["metadata"]["attempt"], 1)
        self.assertEqual(data["outputs"]["source_count"], 1)
        for secret in ("private-tenant", "Bearer SECRET", "private-doc", "sensitive-version", "confidential source"):
            self.assertNotIn(secret, body)

    def test_invalid_or_raw_trace_fields_never_reach_client(self):
        client = Mock(); value = self.trace().model_dump()
        with self.assertRaises(ValidationError): export_langsmith(client, dict(value, raw_query="SECRET"))
        with self.assertRaises(ValidationError): export_langsmith(client, dict(value, attempt=0))
        with self.assertRaises(ValidationError): export_langsmith(client, dict(value, source_sha256=["SECRET"]))
        with self.assertRaises(ValidationError): export_langsmith(client, dict(value, source_count=2))
        client.create_run.assert_not_called()
