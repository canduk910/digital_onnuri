package gift.onnuri.meta;

/**
 * GET /api/meta 응답. 데이터의 "언제 갱신됐는지" 스탬프를 프론트에 전달한다.
 *
 * merchantsCollectedOn = 가맹점 데이터를 마지막으로 갱신(수집)한 날짜(YYYY-MM-DD).
 *   야간 배치가 app_meta['merchants_collected_on']에 기록하며, 값이 없으면 null.
 *   프론트(merchants.html)는 API 모드에서 이 값으로 "○○ 수집" 스탬프를 표시한다
 *   (하드코딩 대체 — 배치가 데이터를 갱신하면 화면 날짜도 따라 올라간다).
 *
 * merchantsStaleSince / merchantsStaleReason = 야간 배치의 가맹점 재수집이 **연속 실패 중**일 때만
 *   채워진다(첫 실패일, 사유 한 줄). 성공하면 배치가 지운다.
 *
 *   왜 필요한가: 2026-08-29 온누리가 가맹점 API 를 v2→v3 로 옮기며 v2 를 닫았고, 배치는
 *   설계대로 fail-open 해 기존 데이터를 지켰다. 그런데 **나흘 동안 아무도 몰랐다** —
 *   화면은 그동안 "매일 00:30 자동 최신화"라고 말하고 있었다. 수집이 멈춘 사실은
 *   운영자의 로그가 아니라 **이용자가 보는 화면**에 드러나야 한다.
 *
 * 온라인 플랫폼 수집일은 /api/online/platforms 응답 meta.collected_on으로 별도 제공된다(대칭).
 */
public record MetaResult(String merchantsCollectedOn,
                         String merchantsStaleSince,
                         String merchantsStaleReason) {
}
