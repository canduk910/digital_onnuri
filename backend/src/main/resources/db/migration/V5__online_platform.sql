-- 온라인 사용처 플랫폼(2026-08-12 백엔드 이관). data/online_platforms.json의 items와 의미상 1:1.
-- 라이브 SSOT는 이 테이블(야간 배치가 공식 API로 갱신), JSON은 프론트 폴백(다소 낡아도 됨) — ADR-14.
CREATE TABLE online_platform (
    id             VARCHAR(60)  PRIMARY KEY,   -- 슬러그(기존 JSON id 유지)
    post_no        INT,                        -- 공식 API postNo(수집 매칭키)
    ord            INT,                        -- 표시 순서(공식 ordNo)
    kind           VARCHAR(20)  NOT NULL,      -- shopping/delivery
    name           VARCHAR(120) NOT NULL,
    summary        VARCHAR(255),
    note           VARCHAR(255),               -- 큐레이션 — 수집이 덮지 않음
    url            VARCHAR(500),
    region_limited BOOLEAN NOT NULL DEFAULT FALSE, -- 큐레이션 — 수집이 덮지 않음
    source_url     VARCHAR(255),
    collected_on   DATE,
    status         VARCHAR(20)  NOT NULL DEFAULT 'active'  -- active/removed
);

-- 정렬(ord ASC NULLS LAST, name)·post_no 매칭 조회 보조.
CREATE INDEX idx_online_platform_ord     ON online_platform (ord);
CREATE INDEX idx_online_platform_post_no ON online_platform (post_no);
