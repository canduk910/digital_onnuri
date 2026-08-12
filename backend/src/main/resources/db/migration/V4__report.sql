-- 버그 제보 게시판(2026-08-11): 익명 제보 — 개인정보 필드 없음(닉네임은 선택 자유입력).
CREATE TABLE report (
    id         BIGSERIAL PRIMARY KEY,
    title      VARCHAR(120)  NOT NULL,
    content    VARCHAR(2000) NOT NULL,
    page       VARCHAR(40),                          -- 어느 화면에서 겪었는지(선택)
    nickname   VARCHAR(40),                          -- 선택 표시명
    status     VARCHAR(20)   NOT NULL DEFAULT '접수',
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_created ON report (created_at DESC);
