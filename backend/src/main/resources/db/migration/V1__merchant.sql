-- 가맹점 정본 테이블. data/merchants/*.json 필드와 1:1.
-- 좌표는 관계형 컬럼으로 두고, 향후 RAG용 임베딩은 별도 테이블/pgvector로 확장.
CREATE TABLE merchant (
    id            VARCHAR(32)  PRIMARY KEY,     -- frCd (가맹점 고유 코드)
    name          VARCHAR(255) NOT NULL,
    cat           VARCHAR(40)  NOT NULL,        -- 업종 대분류(음식점·카페 등)
    brand         VARCHAR(80),                  -- 자동탐지 브랜드명(nullable)
    region        VARCHAR(10)  NOT NULL,        -- 서울/인천/경기
    si            VARCHAR(40),                  -- 경기 시(서울·인천 null)
    gu            VARCHAR(40),                  -- 구(경기 일반시 null)
    dong          VARCHAR(60),                  -- 법정동(파싱, ~7% null)
    addr          VARCHAR(255),                 -- 도로명 주소
    market        VARCHAR(160),                 -- 소속 시장·상점가
    market_type   VARCHAR(40),                  -- 전통시장/골목형상점가 등
    paper         VARCHAR(1),                   -- 지류 Y/N
    card          VARCHAR(1),                   -- 카드형 Y/N
    qr            VARCHAR(1),                   -- 모바일 QR Y/N
    lat           DOUBLE PRECISION,
    lng           DOUBLE PRECISION
);

-- 지역 계층 필터: 서울·인천(region+gu+dong), 경기(region+si+gu+dong)
CREATE INDEX idx_merchant_region_gu_dong ON merchant (region, gu, dong);
CREATE INDEX idx_merchant_region_si_gu   ON merchant (region, si, gu);
CREATE INDEX idx_merchant_cat            ON merchant (cat);
CREATE INDEX idx_merchant_brand          ON merchant (brand);
CREATE INDEX idx_merchant_market_type    ON merchant (market_type);
