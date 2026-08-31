-- 몰별 검색 URL 템플릿 (ADR-17).
-- 이용자가 직접 열 링크다 — 실시간 조회 대상 6곳뿐 아니라 조회하지 않는 몰도 갖는다.
-- "확인하지 않았다"를 "없다"로 오해하지 않게 하려면 갈 곳을 줘야 하기 때문이다.
--
-- {q} 자리에 URL 인코딩된 검색어가 들어간다. 값이 없으면 프론트가 홈(url)으로 보낸다.
-- note·region_limited 와 같은 **큐레이션 필드**다 — 야간 배치(nightly_update.py 단계 B)의
-- UPDATE 는 갱신 컬럼을 명시적으로 나열하므로 이 컬럼은 건드리지 않는다.
ALTER TABLE online_platform ADD COLUMN search_url_template VARCHAR(500);
