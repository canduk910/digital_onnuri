-- 온라인 상품명 전일 색인 (ADR-18).
--
-- 실시간 조회가 열지 못하는 몰(검색 기능이 없거나 시장·주소 선택이 먼저인 곳)에 대해
-- "어제 이 몰이 이 이름의 상품을 올려 두고 있었다"까지를 답하기 위한 층이다.
-- 실시간 층의 상태(none/likely/…)에 섞지 않는다 — "지금 검색된다"와 다른 주장이다.
--
-- 소유권: **야간 배치 단계 F 가 몰 단위로 교체 적재하고, 앱은 읽기만 한다.**
--   앱이 쓰기 시작하면 "반쯤 걷힌 회차"를 앱이 만들어 낼 수 있고 배치의 가드가 그것을 막지 못한다.
--   배치 적재 가드: 몰별 새 건수가 기존의 50% 미만이면 교체하지 않고 기존 색인을 유지한다
--   (단계 A 의 ±20% 가드와 같은 논리 — 반쯤 걷힌 회차로 색인을 덮지 않는다).
--
-- PK 가 (platform_id, url) 인 이유: 같은 상품이 목록·카테고리 양쪽에서 걷혀도 한 행이 된다.
-- platform_id 타입은 online_platform.id(V5, VARCHAR(60))에 맞춘다.
CREATE TABLE online_product_index (
    platform_id  VARCHAR(60)  NOT NULL REFERENCES online_platform(id),
    url          VARCHAR(700) NOT NULL,
    name         VARCHAR(300) NOT NULL,
    collected_on DATE         NOT NULL,
    PRIMARY KEY (platform_id, url)
);

-- 몰 단위 교체 적재(DELETE ... WHERE platform_id = ?)와 몰별 요약 집계를 받친다.
CREATE INDEX idx_online_product_index_platform ON online_product_index (platform_id);
