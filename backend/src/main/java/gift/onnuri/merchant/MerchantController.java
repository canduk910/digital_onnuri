package gift.onnuri.merchant;

import gift.onnuri.merchant.dto.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 가맹점 검색 API. 프론트(merchants.html)가 이 계약으로 데이터를 받는다. */
@RestController
@RequestMapping("/api")
public class MerchantController {

    private final MerchantService svc;

    private ClusterRepository clusters;

    public MerchantController(MerchantService svc, ClusterRepository clusters) {
        this.clusters = clusters;
        this.svc = svc;
    }

    /** 리스트 + 페이징. */
    @GetMapping("/merchants")
    public PageResult<MerchantView> merchants(SearchQuery qy,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size,
                                              @RequestParam(required = false) String sort,
                                              @RequestParam(required = false) Double uLat,
                                              @RequestParam(required = false) Double uLng) {
        return svc.search(qy, page, size, sort, uLat, uLng);
    }

    /** 칩 배지용 집계: 업종별·브랜드별·시장유형별 카운트. */
    @GetMapping("/merchants/facets")
    public Map<String, List<CountItem>> facets(SearchQuery qy) {
        return Map.of(
                "cat", svc.facetsByCat(qy),
                "brand", svc.facetsByBrand(qy),
                "mtype", svc.facetsByMtype(qy)
        );
    }

    /** 지도용 좌표(상한 초과 시 truncated). */
    @GetMapping("/merchants/map")
    public MapResult map(SearchQuery qy) {
        return svc.map(qy);
    }

    /** 지역 계층 옵션(구/동 또는 시/구/동). */
    @GetMapping("/regions")
    public Map<String, List<String>> regions(@RequestParam String region,
                                             @RequestParam(required = false) String si,
                                             @RequestParam(required = false) String gu) {
        return svc.regionTree(region, si, gu);
    }

    /** 브랜드 목록+카운트(상위 N은 프론트가 슬라이스). */
    @GetMapping("/brands")
    public List<CountItem> brands(SearchQuery qy) {
        return svc.facetsByBrand(qy);
    }

    // ---------- POST 병행 (2026-08-11 GET→POST 전환) ----------
    // 검색 필터값이 URL 쿼리스트링(히스토리·프록시·액세스 로그)에 남지 않도록 본문으로 받는다.
    // 프론트는 POST만 사용하고, GET은 운영 curl·회귀 스크립트 호환용으로 유지한다.

    /** 격자 클러스터(2026-08-12) — 상한 초과 뷰용. grid=격자 크기(도 단위). */
    @GetMapping("/merchants/clusters")
    public ClusterResult clustersGet(SearchQuery qy, @RequestParam(defaultValue = "0.1") double grid) {
        return clusters.clusters(qy, grid);
    }

    @PostMapping("/merchants/clusters")
    public ClusterResult clustersPost(@RequestBody SearchBody b,
                                      @RequestParam(defaultValue = "0.1") double grid) {
        return clusters.clusters(b.toQuery(), grid);
    }

    @PostMapping("/merchants")
    public PageResult<MerchantView> merchantsPost(@RequestBody SearchBody b) {
        return svc.search(b.toQuery(),
                b.page() == null ? 0 : b.page(),
                b.size() == null ? 50 : b.size(),
                b.sort(), b.uLat(), b.uLng());
    }

    @PostMapping("/merchants/facets")
    public Map<String, List<CountItem>> facetsPost(@RequestBody SearchBody b) {
        return facets(b.toQuery());
    }

    @PostMapping("/merchants/map")
    public MapResult mapPost(@RequestBody SearchBody b) {
        return svc.map(b.toQuery());
    }

    @PostMapping("/regions")
    public Map<String, List<String>> regionsPost(@RequestBody SearchBody b) {
        return svc.regionTree(b.region(), b.si(), b.gu());
    }

    @PostMapping("/brands")
    public List<CountItem> brandsPost(@RequestBody SearchBody b) {
        return svc.facetsByBrand(b.toQuery());
    }
}
