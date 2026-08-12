-- 방문 집계(2026-08-11): 개인정보 없이 일자별 카운트만 저장한다(IP·식별자 미저장).
-- 클라이언트(shell.js)가 브라우저 세션당 1회 POST /api/visit로 증가시킨다.
CREATE TABLE visit_daily (
    day   DATE   PRIMARY KEY,
    count BIGINT NOT NULL DEFAULT 0
);
