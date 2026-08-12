-- 애플리케이션 메타 키-값. 데이터 자체가 아니라 "언제 갱신됐는지" 같은 운영 메타를 담는다.
-- 첫 용도: merchants_collected_on — 야간 배치가 가맹점 데이터를 갱신한 날짜.
--   merchant 테이블은 78K행이 모두 같은 수집일을 가지므로 컬럼으로 두지 않고 여기 단일 행에 기록한다.
--   프론트(merchants.html)는 API 모드에서 이 값을 읽어 "○○ 수집" 스탬프를 실제 갱신일로 표시한다.
CREATE TABLE app_meta (
    k VARCHAR(60)  PRIMARY KEY,
    v VARCHAR(255)
);
