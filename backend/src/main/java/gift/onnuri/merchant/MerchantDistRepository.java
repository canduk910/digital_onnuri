package gift.onnuri.merchant;

import gift.onnuri.merchant.dto.SearchQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 가까운 순(sort=dist) 전용 조회. 정렬 키가 "컬럼"이 아니라 "수식"(사용자 좌표와의 거리)이라
 * Spring Data의 Sort(프로퍼티 경로 파싱)로는 표현할 수 없어 CriteriaBuilder로 직접 만든다.
 *
 * 거리 근사: 위도차² + 경도차²·cos²(사용자 위도) — 순위만 필요하므로 하버사인 불필요(시도 스케일에서 순서 동일).
 * WHERE는 MerchantSpecs.from(qy)을 그대로 재사용한다(경계면 1:1 원칙 — 목록·지도와 같은 필터).
 * 좌표 없는 행은 ASC 정렬에서 뒤로 밀린다(PG 기본 NULLS LAST).
 */
@Repository
public class MerchantDistRepository {

    @PersistenceContext
    private EntityManager em;

    public List<Merchant> findNearest(SearchQuery qy, double uLat, double uLng, int page, int size) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Merchant> cq = cb.createQuery(Merchant.class);
        Root<Merchant> root = cq.from(Merchant.class);
        Predicate where = MerchantSpecs.from(qy).toPredicate(root, cq, cb);
        if (where != null) cq.where(where);

        double c2 = Math.pow(Math.cos(Math.toRadians(uLat)), 2);
        Expression<Double> dLat = cb.diff(root.get("lat"), uLat);
        Expression<Double> dLng = cb.diff(root.get("lng"), uLng);
        Expression<Double> dist = cb.sum(cb.prod(dLat, dLat), cb.prod(cb.prod(dLng, dLng), c2));
        cq.orderBy(cb.asc(dist));

        return em.createQuery(cq)
                .setFirstResult(Math.max(page, 0) * size)
                .setMaxResults(size)
                .getResultList();
    }
}
