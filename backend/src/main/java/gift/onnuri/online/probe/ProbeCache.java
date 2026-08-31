package gift.onnuri.online.probe;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 질의별 결과 캐시. 상대 사이트로 나가는 요청을 줄이는 것이 첫째 목적이고,
 * 응답 속도는 그 부수 효과다.
 *
 * NewsService 의 volatile + TTL 을 여러 키로 확장한 형태. 단일 인스턴스 배포(ADR-5)라
 * 분산 캐시는 두지 않는다. 접근 순서 LRU 로 상한을 지킨다.
 */
@Component
public class ProbeCache {

    record Entry(OnlineSearchResult result, long at) {}

    private final long ttlMs;
    private final Map<String, Entry> map;

    public ProbeCache(@Value("${app.online.probe.ttl-minutes:60}") int ttlMinutes,
                      @Value("${app.online.probe.cache-size:500}") int maxSize) {
        this.ttlMs = ttlMinutes * 60_000L;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Entry> e) {
                return size() > maxSize;
            }
        };
    }

    public synchronized OnlineSearchResult get(String key) {
        Entry e = map.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.at() > ttlMs) {
            map.remove(key);
            return null;
        }
        return e.result();
    }

    public synchronized void put(String key, OnlineSearchResult result) {
        map.put(key, new Entry(result, System.currentTimeMillis()));
    }

    synchronized int size() { return map.size(); }
}
