package gift.onnuri.meta;

/**
 * GET /api/meta 응답. 데이터의 "언제 갱신됐는지" 스탬프를 프론트에 전달한다.
 *
 * merchantsCollectedOn = 가맹점 데이터를 마지막으로 갱신(수집)한 날짜(YYYY-MM-DD).
 *   야간 배치가 app_meta['merchants_collected_on']에 기록하며, 값이 없으면 null.
 *   프론트(merchants.html)는 API 모드에서 이 값으로 "○○ 수집" 스탬프를 표시한다
 *   (하드코딩 대체 — 배치가 데이터를 갱신하면 화면 날짜도 따라 올라간다).
 *
 * 온라인 플랫폼 수집일은 /api/online/platforms 응답 meta.collected_on으로 별도 제공된다(대칭).
 */
public record MetaResult(String merchantsCollectedOn) {
}
