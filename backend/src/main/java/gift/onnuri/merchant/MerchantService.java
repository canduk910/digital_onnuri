package gift.onnuri.merchant;

import gift.onnuri.merchant.dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** 검색·집계·지역 계층·브랜드 로직. 프론트(merchants.html) 규칙을 서버로 옮긴 것. */
@Service
public class MerchantService {

    private final MerchantRepository repo;
    private final int mapMax;

    public MerchantService(MerchantRepository repo,
                           @Value("${app.map.max-markers:3000}") int mapMax) {
        this.repo = repo;
        this.mapMax = mapMax;
    }

    /** 리스트 + 페이징. sort: "name"=가맹점명순, 그 외(기본)=id(수집순 근사). */
    public PageResult<MerchantView> search(SearchQuery qy, int page, int size, String sort) {
        Sort order = "name".equals(sort) ? Sort.by("name") : Sort.by("id");
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), order);
        Page<Merchant> p = repo.findAll(MerchantSpecs.from(qy), pageable);
        List<MerchantView> items = p.getContent().stream().map(MerchantView::of).toList();
        return new PageResult<>(items, p.getTotalElements(), p.getNumber(), p.getSize());
    }

    /** 업종(cat)별 카운트 — 필터 컨텍스트 유지, cat 자신은 제외해 칩 배지를 계산. */
    public List<CountItem> facetsByCat(SearchQuery qy) {
        SearchQuery ctx = withCat(qy, null);
        List<Merchant> rows = repo.findAll(MerchantSpecs.from(ctx));
        return countBy(rows, Merchant::getCat);
    }

    /** 브랜드별 카운트 — 필터 컨텍스트 유지, brand 자신은 제외. null 브랜드는 제외. */
    public List<CountItem> facetsByBrand(SearchQuery qy) {
        SearchQuery ctx = withBrand(qy, null);
        List<Merchant> rows = repo.findAll(MerchantSpecs.from(ctx));
        return rows.stream()
                .map(Merchant::getBrand)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(b -> b, Collectors.counting()))
                .entrySet().stream()
                .map(e -> new CountItem(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(CountItem::count).reversed()
                        .thenComparing(CountItem::key))
                .toList();
    }

    /** 시장 유형(market_type)별 카운트 — 필터 컨텍스트 유지, mtype 자신은 제외. */
    public List<CountItem> facetsByMtype(SearchQuery qy) {
        SearchQuery ctx = withMtype(qy, null);
        List<Merchant> rows = repo.findAll(MerchantSpecs.from(ctx));
        return countBy(rows, Merchant::getMarketType);
    }

    /** 지도 좌표 — 상한 초과 시 마커 생략(truncated). */
    public MapResult map(SearchQuery qy) {
        long total = repo.count(MerchantSpecs.from(qy));
        if (total > mapMax) {
            return new MapResult(List.of(), total, true);
        }
        List<MapResult.MapPin> pins = repo.findAll(MerchantSpecs.from(qy)).stream()
                .filter(m -> m.getLat() != null && m.getLng() != null)
                .map(m -> new MapResult.MapPin(m.getId(), m.getName(), m.getCat(), m.getBrand(),
                        m.getAddr(), m.getMarket(), m.getCard(), m.getQr(), m.getLat(), m.getLng()))
                .toList();
        return new MapResult(pins, total, false);
    }

    /** 지역 계층 옵션: 구 목록, (경기)시 목록, 특정 상위 하위 동/구 목록. */
    public Map<String, List<String>> regionTree(String region, String si, String gu) {
        SearchQuery base = new SearchQuery(region, si, gu, null, null, null, null, null, null, null, null, null, null);
        List<Merchant> rows = repo.findAll(MerchantSpecs.from(new SearchQuery(region, null, null, null, null, null, null, null, null, null, null, null, null)));
        Map<String, List<String>> out = new LinkedHashMap<>();
        boolean gyeonggi = "경기".equals(region);
        if (gyeonggi) {
            out.put("si", distinct(rows, Merchant::getSi));
            if (si != null && !si.isBlank()) {
                List<Merchant> inSi = rows.stream().filter(m -> si.equals(m.getSi())).toList();
                out.put("gu", distinct(inSi, Merchant::getGu));
                List<Merchant> scope = (gu != null && !gu.isBlank())
                        ? inSi.stream().filter(m -> gu.equals(m.getGu())).toList() : inSi;
                out.put("dong", distinctDong(scope));
            }
        } else {
            out.put("gu", distinct(rows, Merchant::getGu));
            if (gu != null && !gu.isBlank()) {
                List<Merchant> inGu = rows.stream().filter(m -> gu.equals(m.getGu())).toList();
                out.put("dong", distinctDong(inGu));
            }
        }
        return out;
    }

    // ---- helpers ----
    private static List<CountItem> countBy(List<Merchant> rows, java.util.function.Function<Merchant, String> key) {
        return rows.stream()
                .map(key).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(k -> k, Collectors.counting()))
                .entrySet().stream()
                .map(e -> new CountItem(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(CountItem::count).reversed().thenComparing(CountItem::key))
                .toList();
    }

    private static List<String> distinct(List<Merchant> rows, java.util.function.Function<Merchant, String> f) {
        return rows.stream().map(f).filter(Objects::nonNull).distinct().sorted().toList();
    }

    /** 동 목록: 실제 동 + (파싱 실패분 있으면) "동미상" 센티넬. */
    private static List<String> distinctDong(List<Merchant> rows) {
        List<String> dongs = new ArrayList<>(rows.stream()
                .map(Merchant::getDong).filter(Objects::nonNull).distinct().sorted().toList());
        boolean hasUnknown = rows.stream().anyMatch(m -> m.getDong() == null);
        if (hasUnknown) dongs.add(SearchQuery.UNKNOWN_DONG);
        return dongs;
    }

    private static SearchQuery withCat(SearchQuery q, String cat) {
        return new SearchQuery(q.region(), q.si(), q.gu(), q.dong(), cat, q.brand(), q.mtype(), q.digital(), q.q(),
                q.minLat(), q.maxLat(), q.minLng(), q.maxLng());
    }
    private static SearchQuery withBrand(SearchQuery q, String brand) {
        return new SearchQuery(q.region(), q.si(), q.gu(), q.dong(), q.cat(), brand, q.mtype(), q.digital(), q.q(),
                q.minLat(), q.maxLat(), q.minLng(), q.maxLng());
    }
    private static SearchQuery withMtype(SearchQuery q, String mtype) {
        return new SearchQuery(q.region(), q.si(), q.gu(), q.dong(), q.cat(), q.brand(), mtype, q.digital(), q.q(),
                q.minLat(), q.maxLat(), q.minLng(), q.maxLng());
    }
}
