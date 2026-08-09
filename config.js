// 가맹점 검색 데이터 소스 설정 (merchants.html).
// 백엔드(Spring API)가 아직 배포되지 않았거나 내려가 있어도 페이지가 정상 동작하도록,
// dataMode로 데이터 소스를 분기한다. 배포·검증이 끝나면 "api"로 고정해도 된다.
window.ONNURI_CONFIG = {
  // "auto" : API를 짧게 프로브해 응답하면 API, 실패하면 기존 JSON(data/merchants/*.json)으로 폴백 (권장)
  // "api"  : 항상 백엔드 API 사용 (백엔드 미기동 시 오류 화면)
  // "json" : 항상 로컬 JSON 사용 (백엔드 무시, 프로브 지연 없음)
  // 백엔드 미배포 상태 → 라이브는 "json". NCP 배포·검증 후 "auto"(또는 "api")로 전환.
  dataMode: "json",

  // (선택) API_BASE 강제 지정. 미지정 시 로컬은 localhost:8080, 그 외는 배포 도메인 사용.
  // apiBase: "https://api.koscomlabor.cloud/api",

  // auto 모드 프로브 제한시간(ms)
  probeTimeoutMs: 2500
};
