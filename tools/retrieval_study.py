"""Compare local source finding through LangChain and a pinned encoder.
Uses document_study OCR receipts with page and token chunk strategies.
Tenant/current-source filtering precedes ranking; source text stays inert.
"""
from __future__ import annotations
import argparse
import json
import math
import time
from collections import Counter
from pathlib import Path

from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings
from langchain_core.runnables import RunnableLambda
from langchain_core.vectorstores import InMemoryVectorStore
from langchain_text_splitters import RecursiveCharacterTextSplitter
from source_finding import TOKEN
from document_study import digest, encode, validate

MODEL = "sentence-transformers/paraphrase-MiniLM-L3-v2"
REVISION = "4ca70771034acceecb2e72475f72050fcdde4ddc"


class SentenceEmbeddings(Embeddings):
    def __init__(self, path):
        from sentence_transformers import SentenceTransformer
        import torch
        torch.set_num_threads(2)
        self.model = SentenceTransformer(str(path), device="cpu", local_files_only=True)
        self.tokenizer = self.model.tokenizer

    def embed_documents(self, texts):
        return self.model.encode(texts, batch_size=16, normalize_embeddings=True, show_progress_bar=False).tolist()

    def embed_query(self, text):
        return self.embed_documents([text])[0]


class SourceIndex:
    def __init__(self, pages, embeddings, tokenizer):
        self.pages = [dict(page) for page in pages]
        self.embeddings = embeddings
        self.tokenizer = tokenizer
        for page in self.pages:
            for key in ("tenant_id", "document_id", "source_version", "page", "text", "entity"):
                if key not in page or page[key] in (None, ""):
                    raise ValueError(f"page missing {key}")
            if type(page["page"]) is not int or page["page"] < 1:
                raise ValueError("page must be a positive integer")
        self.revoked = set()
        # Retain one visible scope. Revisions prevent a late old build becoming current.
        self._prepared = None
        self._revision = 0

    def revoke(self, tenant, document):
        self.revoked.add((tenant, document))
        self._prepared = None
        self._revision += 1

    def delete(self, tenant, document):
        self.pages = [p for p in self.pages if (p["tenant_id"], p["document_id"]) != (tenant, document)]
        self._prepared = None
        self._revision += 1

    def _tokens(self, text):
        return self.tokenizer.encode(text, add_special_tokens=False)

    def chunks(self, tenant, current_versions, strategy, chunk_tokens):
        if not tenant or not isinstance(current_versions, dict):
            raise ValueError("explicit tenant and current source versions required")
        if strategy not in ("page", "token") or chunk_tokens < 1:
            raise ValueError("invalid chunk policy")
        docs = [Document(page_content=str(p["text"]), metadata={k: p[k] for k in ("tenant_id", "document_id", "source_version", "page", "entity")})
                for p in self.pages if p["tenant_id"] == tenant and (tenant, p["document_id"]) not in self.revoked
                and current_versions.get(p["document_id"]) == p["source_version"]]
        if strategy == "page":
            return [Document(page_content=self.tokenizer.decode(self._tokens(d.page_content)[:chunk_tokens]), metadata=d.metadata) for d in docs]
        splitter = RecursiveCharacterTextSplitter(chunk_size=chunk_tokens, chunk_overlap=0,
                    length_function=lambda s: len(self._tokens(s)), separators=["\n", " ", ""], add_start_index=True)
        return splitter.split_documents(docs)

    def query(self, tenant, current_versions, query, *, method="bm25", strategy="token", k=3, context_tokens=120, chunk_tokens=80):
        if method not in ("bm25", "dense", "hybrid") or not query.strip() or k < 1 or context_tokens < 1:
            raise ValueError("invalid query policy")
        if not tenant or not isinstance(current_versions, dict):
            raise ValueError("explicit tenant and current source versions required")
        current_versions = dict(current_versions)
        scope = (tenant, tuple(sorted(current_versions.items())), strategy, chunk_tokens, self._revision)
        prepared = self._prepared
        if prepared is None or prepared[0] != scope or (method != "bm25" and prepared[2] is None):
            chunks = self.chunks(tenant, current_versions, strategy, chunk_tokens)
            store, positions = None, {}
            if method != "bm25" and chunks:
                store = InMemoryVectorStore(self.embeddings)
                positions = {value: i for i, value in enumerate(store.add_documents(chunks))}
            prepared = (scope, chunks, store, positions)
            self._prepared = prepared
        _, chunks, store, positions = prepared
        if not chunks: return {"sources": [], "abstained": True, "context_tokens": 0}
        terms = [Counter(t.casefold() for t in TOKEN.findall(d.page_content)) for d in chunks]
        query_terms = set(t.casefold() for t in TOKEN.findall(query))
        length = [sum(c.values()) for c in terms]
        average = sum(length) / len(length) or 1
        scores = []
        for counts, size in zip(terms, length):
            score = 0.0
            for term in query_terms:
                df = sum(term in c for c in terms)
                idf = math.log(1 + (len(terms) - df + .5) / (df + .5))
                tf = counts[term]
                score += idf * tf * 2.2 / (tf + 1.2 * (.25 + .75 * size / average))
            scores.append(score)
        sparse = sorted(range(len(chunks)), key=lambda i: (-scores[i], i))
        dense = []
        if method != "bm25":
            # Capture the prepared scope locally so another query cannot swap its store.
            matches = RunnableLambda(lambda question: store.similarity_search_with_score(question, k=len(chunks))).invoke(query)
            dense = [(positions[d.id], float(s)) for d, s in matches]
        if method == "bm25": ranking = [(i, scores[i]) for i in sparse if scores[i] > 0]
        elif method == "dense": ranking = [(i, score) for i, score in dense if score >= .2]
        else:
            fusion = Counter()
            for ranking_list in ([i for i in sparse if scores[i] > 0], [i for i, s in dense if s >= .2]):
                for rank, i in enumerate(ranking_list, 1): fusion[i] += 1 / (60 + rank)
            ranking = sorted(fusion.items(), key=lambda x: (-x[1], x[0]))
        sources, seen, used = [], set(), 0
        for index, score in ranking:
            doc = chunks[index]
            identity = tuple(doc.metadata[k] for k in ("document_id", "source_version", "page"))
            if identity in seen: continue
            tokens = self._tokens(doc.page_content)[:context_tokens - used]
            if not tokens: break
            sources.append({**doc.metadata, "text": self.tokenizer.decode(tokens), "score": score})
            seen.add(identity); used += len(tokens)
            if len(sources) == k or used == context_tokens: break
        return {"sources": sources, "abstained": not sources, "context_tokens": used}


def run(manifest_path, ocr_cases_path, model_path, protocol_path, output):
    manifest = validate(json.loads(manifest_path.read_text()), manifest_path.parent)
    protocol = json.loads(protocol_path.read_text())
    if (protocol["manifest_sha256"] != digest(manifest_path) or protocol["ocr_cases_sha256"] != digest(ocr_cases_path)
            or protocol["ocr_observations_sha256"] != digest(ocr_cases_path.with_name("observations.json"))
            or protocol["retrieval_code_sha256"] != digest(Path(__file__))):
        raise ValueError("frozen retrieval inputs changed")
    for name, sha in protocol["model_sha256"].items():
        if digest(model_path / name) != sha: raise ValueError("frozen encoder changed")
    embeddings = SentenceEmbeddings(model_path)
    raw_pages = {(c["document_id"], c["page"]): c for c in json.loads(ocr_cases_path.with_name("observations.json").read_text())}
    pages, queries, versions = [], [], {}
    for doc in manifest["documents"]:
        if doc["split"] != "test": continue
        versions[doc["document_id"]] = doc["source_version"]
        for page in doc["pages"]:
            # Source finding uses OCR text emitted by the independent OCR stage, not label text.
            raw = raw_pages[(doc["document_id"], page["page"])]
            pages.append({"tenant_id": doc["tenant_id"], "document_id": doc["document_id"], "source_version": doc["source_version"],
                          "page": page["page"], "entity": doc["issuer"], "text": raw["ocr_text"] or "[empty OCR]"})
            if page["expected"]:
                f = page["expected"][0]
                queries.append({"query": f'{doc["family"]} {doc["document_id"].rsplit("-", 1)[1]} REVENUE {f["value"]} {f["unit"]} {f["period"]}',
                                "document_id": doc["document_id"], "source_version": doc["source_version"], "page": page["page"]})
    index = SourceIndex(pages, embeddings, embeddings.tokenizer)
    output.mkdir(parents=True, exist_ok=False)
    rows, summaries = [], []
    for strategy in ("page", "token"):
        for method in ("bm25", "dense", "hybrid"):
            index.query("fixture-tenant", versions, queries[0]["query"], method=method, strategy=strategy)
            for query in queries:
                start = time.perf_counter()
                result = index.query("fixture-tenant", versions, query["query"], method=method, strategy=strategy)
                elapsed = time.perf_counter() - start
                rank = next((r for r, s in enumerate(result["sources"], 1) if all(s[k] == query[k] for k in ("document_id", "source_version", "page"))), None)
                rows.append({"method": method, "strategy": strategy, **query, "rank": rank, "seconds": elapsed, **result})
            selected = [r for r in rows if r["method"] == method and r["strategy"] == strategy]
            summaries.append({"method": method, "strategy": strategy, "queries": len(selected), "recall_at_3": sum(r["rank"] is not None for r in selected) / len(selected),
                              "mrr_at_3": sum(1 / r["rank"] if r["rank"] else 0 for r in selected) / len(selected),
                              "p95_seconds": sorted(r["seconds"] for r in selected)[int(.95 * len(selected)) - 1]})
    summary = {"cohort": manifest["cohort"], "protocol_sha256": digest(protocol_path), "methods": summaries,
               "limitations": ["Generated fixtures and field-literal queries; not independent reviewer tasks or representative semantic retrieval.",
                               "Warmups prepare each axis; the current visible index is reused only while scope matches. Timings require an isolated run.",
                               "No generated answers, reranker, HyDE, provider calls or human correction time measured."]}
    (output / "observations.json").write_text(encode(rows)); (output / "summary.json").write_text(encode(summary))
    return summary


def freeze(manifest, ocr_cases, model, protocol):
    value = {"schema_version": 1, "manifest_sha256": digest(manifest), "ocr_cases_sha256": digest(ocr_cases),
             "ocr_observations_sha256": digest(ocr_cases.with_name("observations.json")), "retrieval_code_sha256": digest(Path(__file__)),
             "model": MODEL, "revision": REVISION, "model_sha256": {str(p.relative_to(model)): digest(p) for p in model.rglob("*") if p.is_file() and ".cache" not in p.parts},
             "strategies": ["page truncated to 80 encoder tokens", "page-boundary-preserving token chunks, 80 tokens, no overlap"],
             "methods": ["BM25 k1=1.2 b=.75", "dense cosine, abstain below .2", "RRF k=60"], "context_tokens": 120, "k": 3,
             "unit": "document, repeated page queries are dependent", "split": "test", "tuning": "none", "repetitions": 1, "warmups_per_axis": 1,
             "minimum_effect": "descriptive only, no uplift claim on generated fixtures"}
    with protocol.open("x") as out: out.write(encode(value))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["freeze", "run"])
    for name in ("manifest", "ocr_cases", "model", "protocol"): parser.add_argument(name, type=Path)
    parser.add_argument("output", type=Path, nargs="?")
    args = parser.parse_args()
    if args.action == "freeze": freeze(args.manifest, args.ocr_cases, args.model, args.protocol)
    else: run(args.manifest, args.ocr_cases, args.model, args.protocol, args.output)
