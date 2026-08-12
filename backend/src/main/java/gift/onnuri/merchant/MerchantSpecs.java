package gift.onnuri.merchant;

import gift.onnuri.merchant.dto.SearchQuery;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/** SearchQuery → JPA Specification. 프론트 필터 규칙과 1:1. */
public final class MerchantSpecs {

    private MerchantSpecs() {}

    public static Specification<Merchant> from(SearchQuery qy) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();

            if (has(qy.region())) ps.add(cb.equal(root.get("region"), qy.region()));

            // 뷰포트 자동 지도(2026-08-12): bounds는 지역 계층을 대체하지 않고 추가 AND 조건이다
            // (지도 = 필터 ∩ 현재 화면). 프론트 JSON 폴백·ClusterRepository와 동일 규칙.
            if (qy.hasBounds()) {
                ps.add(cb.between(root.get("lat"), qy.minLat(), qy.maxLat()));
                ps.add(cb.between(root.get("lng"), qy.minLng(), qy.maxLng()));
            }
            if (has(qy.si())) ps.add(cb.equal(root.get("si"), qy.si()));
            if (has(qy.gu())) ps.add(cb.equal(root.get("gu"), qy.gu()));
            if (has(qy.dong())) {
                if (SearchQuery.UNKNOWN_DONG.equals(qy.dong())) {
                    ps.add(cb.isNull(root.get("dong")));   // 동 미상 = 파싱 실패분
                } else {
                    ps.add(cb.equal(root.get("dong"), qy.dong()));
                }
            }

            // 다중 필터(2026-08-12): 콤마 구분 → IN. 규칙은 FilterCsv·ClusterRepository·프론트 폴백 공유.
            java.util.List<String> cats = gift.onnuri.merchant.dto.FilterCsv.parse(qy.cat());
            if (cats != null)   ps.add(root.get("cat").in(cats));
            java.util.List<String> brands = gift.onnuri.merchant.dto.FilterCsv.parse(qy.brand());
            if (brands != null) ps.add(root.get("brand").in(brands));
            java.util.List<String> mtypes = gift.onnuri.merchant.dto.FilterCsv.parse(qy.mtype());
            if (mtypes != null) ps.add(root.get("marketType").in(mtypes));

            if (Boolean.TRUE.equals(qy.digital())) {
                ps.add(cb.or(cb.equal(root.get("card"), "Y"), cb.equal(root.get("qr"), "Y")));
            }

            if (has(qy.q())) {
                String like = "%" + qy.q().trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("addr"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("market"), "")), like)
                ));
            }

            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private static boolean has(String s) {
        return s != null && !s.isBlank() && !"전체".equals(s);
    }
}
