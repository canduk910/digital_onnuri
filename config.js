// 가맹점 검색 데이터 소스 설정 (merchants.html).
// 백엔드(Spring API)가 아직 배포되지 않았거나 내려가 있어도 페이지가 정상 동작하도록,
// dataMode로 데이터 소스를 분기한다. 배포·검증이 끝나면 "api"로 고정해도 된다.
window.ONNURI_CONFIG = {
  // "auto" : API를 짧게 프로브해 응답하면 API, 실패하면 기존 JSON(data/merchants/*.json)으로 폴백 (권장)
  // "api"  : 항상 백엔드 API 사용 (백엔드 미기동 시 오류 화면)
  // "json" : 항상 로컬 JSON 사용 (백엔드 무시, 프로브 지연 없음)
  // 2026-08-10 NCP 배포·검증 완료(api.koscomlabor.cloud) → "auto": API 우선, 장애 시 JSON 자동 폴백.
  dataMode: "auto",

  // (선택) API_BASE 강제 지정. 미지정 시 로컬은 localhost:8080, 그 외는 배포 도메인 사용.
  // apiBase: "https://api.koscomlabor.cloud/api",

  // auto 모드 프로브 제한시간(ms)
  probeTimeoutMs: 2500,

  // 데이터(JSON) 캐시 무력화용 버전 — data/merchants/*.json 갱신 때마다 올린다(수집일 사용 권장).
  // 파일명이 동일해 브라우저가 옛 데이터를 캐시하는 것을 방지.
  dataVersion: "2026-09-06.6",

  // 챗봇 위젯(ADR-12) 표시 여부 — 장애·비용 이슈 시 false로 배포하면 즉시 숨김(다른 기능 무영향).
  // 2026-08-11 서버 키 등록·적재·라이브 검증 완료 → 활성화.
  chatEnabled: true
};

// 화면 폭 토글 로직은 shell.js로 이관 (ADR-9) — 이 파일은 데이터 소스 설정만 담는다.
