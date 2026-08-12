package gift.onnuri.online;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 온라인 플랫폼 목록 + "기준" 스탬프 조립. */
@Service
public class OnlineService {

    static final String SOURCE = "온누리상품권 공식 홈페이지 〉 온라인 전통시장관";
    static final String SOURCE_URL = "https://www.onnuri.gift/visit/market";

    private final OnlineRepository repo;

    public OnlineService(OnlineRepository repo) {
        this.repo = repo;
    }

    public OnlineResult platforms() {
        List<OnlinePlatformView> items = repo.findAll();
        // meta.collected_on = active 항목의 min(collected_on)("기준" 원칙). active 없으면 null.
        Map<String, String> meta = new HashMap<>();
        meta.put("collected_on", repo.minActiveCollectedOn());
        meta.put("source", SOURCE);
        meta.put("sourceUrl", SOURCE_URL);
        return new OnlineResult(meta, items);
    }
}
