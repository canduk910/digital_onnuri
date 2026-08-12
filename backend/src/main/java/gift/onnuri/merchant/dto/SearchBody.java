package gift.onnuri.merchant.dto;

/**
 * POST 검색 요청 본문 = SearchQuery 필드 + 페이징 (2026-08-11 GET→POST 전환).
 * 검색 필터값이 URL 쿼리스트링(브라우저 히스토리·프록시·서버 액세스 로그)에 남지 않도록
 * 본문으로 옮겼다. 컴포넌트명은 SearchBodyTest가 고정(프론트 apiCall과 한 변경 단위).
 */
public record SearchBody(
        String region,
        String si,
        String gu,
        String dong,
        String cat,
        String brand,
        String mtype,
        Boolean digital,
        String q,
        Double minLat,
        Double maxLat,
        Double minLng,
        Double maxLng,
        Integer page,
        Integer size,
        String sort
) {
    public SearchQuery toQuery() {
        return new SearchQuery(region, si, gu, dong, cat, brand, mtype, digital, q,
                minLat, maxLat, minLng, maxLng);
    }
}
