package gift.onnuri.merchant.dto;

/**
 * 가맹점 검색 필터. 기존 merchants.html의 필터 규칙을 그대로 옮긴다.
 * - 지역 계층: 서울·인천 = region+gu+dong / 경기 = region+si+gu+dong
 * - dong == "동미상"이면 dong IS NULL 로 해석(파싱 실패분)
 * - digital == true 이면 card='Y' OR qr='Y'
 * - q: 가맹점명·주소·시장명 부분 검색
 */
public record SearchQuery(
        String region,
        String si,
        String gu,
        String dong,
        String cat,
        String brand,
        String mtype,
        Boolean digital,
        String q,
        Double minLat,   // 지도범위 검색(bounds) — 4개 모두 있을 때만 적용
        Double maxLat,
        Double minLng,
        Double maxLng
) {
    /** bounds 4개가 모두 지정됐는지(지도범위 검색 모드). */
    public boolean hasBounds() {
        return minLat != null && maxLat != null && minLng != null && maxLng != null;
    }
    /** 파싱 실패 동을 나타내는 프론트 표기와 동일한 센티넬(merchants.html의 "동 미상"과 일치). */
    public static final String UNKNOWN_DONG = "동 미상";
}
