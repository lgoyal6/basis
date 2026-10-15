"""Find page-grounded sources through actual LangChain retrieval APIs.
Provides the lexical baseline and embedding interface used by retrieval_study.
In-memory bag-of-words vectors are not a semantic model or hosted service.
"""
from __future__ import annotations

import math
import re
from collections import Counter
from collections.abc import Iterable, Mapping

from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings
from langchain_core.runnables import RunnableLambda
from langchain_core.vectorstores import InMemoryVectorStore
from langchain_text_splitters import RecursiveCharacterTextSplitter


TOKEN = re.compile(r"[a-z0-9]+", re.IGNORECASE)


class BagOfWordsEmbeddings(Embeddings):
    """Small deterministic lexical vector baseline for offline source finding."""

    def __init__(self, vocabulary: list[str]):
        self.vocabulary = tuple(vocabulary)
        self.positions = {term: index for index, term in enumerate(self.vocabulary)}

    def _embed(self, text: str) -> list[float]:
        counts = Counter(token.casefold() for token in TOKEN.findall(text))
        vector = [float(counts[term]) for term in self.vocabulary]
        norm = math.sqrt(sum(value * value for value in vector))
        return [value / norm for value in vector] if norm else vector

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return [self._embed(text) for text in texts]

    def embed_query(self, text: str) -> list[float]:
        return self._embed(text)


def find_sources(
    pages: Iterable[Mapping[str, object]], query: str, *, k: int = 3, chunk_size: int = 400, chunk_overlap: int = 40
) -> dict[str, object]:
    if not query.strip() or k < 1:
        raise ValueError("query must be non-empty and k must be positive")
    page_docs = []
    for page in pages:
        for key in ("document_id", "source_version", "page", "text"):
            if key not in page or page[key] in (None, ""):
                raise ValueError(f"page missing {key}")
        if type(page["page"]) is not int or page["page"] < 1:
            raise ValueError("page number must be a positive integer")
        page_docs.append(Document(page_content=str(page["text"]), metadata={
            "document_id": str(page["document_id"]), "source_version": str(page["source_version"]), "page": page["page"],
        }))
    if not page_docs:
        raise ValueError("pages must not be empty")

    splitter = RecursiveCharacterTextSplitter(chunk_size=chunk_size, chunk_overlap=chunk_overlap, add_start_index=True)
    chunks = splitter.split_documents(page_docs)
    vocabulary = sorted({token.casefold() for doc in chunks for token in TOKEN.findall(doc.page_content)})
    if not vocabulary:
        return {"query": query, "retrieval": "langchain_inmemory_normalized_bow_cosine", "sources": []}
    store = InMemoryVectorStore(BagOfWordsEmbeddings(vocabulary))
    store.add_documents(chunks)
    retriever = store.as_retriever(search_kwargs={"k": min(k, len(chunks))})
    chain = RunnableLambda(lambda question: {"query": question, "sources": retriever.invoke(question)})
    result = chain.invoke(query)
    return {
        "query": query,
        "retrieval": "langchain_inmemory_normalized_bow_cosine",
        "sources": [{"document_id": doc.metadata["document_id"], "source_version": doc.metadata["source_version"],
                     "page": doc.metadata["page"], "start_index": doc.metadata.get("start_index"), "text": doc.page_content}
                    for doc in result["sources"]],
        "limitations": ["Local lexical embeddings and in-memory vectors are a retrieval baseline, not a pretrained semantic model or hosted vector service."],
    }
