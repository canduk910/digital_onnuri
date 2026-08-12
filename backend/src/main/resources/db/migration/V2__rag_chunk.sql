-- 챗봇 RAG 지식베이스 청크 (ADR-12).
-- 원천: _workspace/rag_corpus/*.md(공식 사이트 채록) + 검증된 내부 정책 문서·SSOT JSON.
-- 적재는 _workspace/dev_scripts/build_rag_corpus.py가 전체 교체 방식으로 수행(멱등).
-- 주의: pgvector 확장이 필요하다 — db 이미지는 pgvector/pgvector:pg16 (ADR-5에서 예정된 교체).
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE rag_chunk (
    id           SERIAL PRIMARY KEY,
    source       VARCHAR(120) NOT NULL,   -- 문서 식별자(코퍼스 파일명)
    section      VARCHAR(255),            -- 제목 경로(예: "사용 요건 > 카드형")
    content      TEXT NOT NULL,
    url          VARCHAR(500),            -- 공식 출처 URL(내부 자산은 null 가능)
    collected_on VARCHAR(10),             -- 수집일 YYYY-MM-DD (기준일 스탬프 원칙)
    embedding    vector(1536)             -- text-embedding-3-small
);

-- HNSW: 수백 청크 규모에서 학습(clustering) 불필요·재적재에 안정적 (ivfflat은 데이터 선행 필요)
CREATE INDEX idx_rag_chunk_embedding ON rag_chunk
    USING hnsw (embedding vector_cosine_ops);
