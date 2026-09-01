package gift.onnuri.meta;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/meta — 데이터 갱신 스탬프. 프론트가 "○○ 수집" 표시에 쓴다. */
@RestController
@RequestMapping("/api")
public class MetaController {

    static final String KEY_MERCHANTS_COLLECTED_ON = "merchants_collected_on";
    /** 배치가 재수집에 연속 실패하는 동안만 존재한다(성공 시 삭제). nightly_update.py 단계 A 와 짝. */
    static final String KEY_MERCHANTS_STALE_SINCE  = "merchants_stale_since";
    static final String KEY_MERCHANTS_STALE_REASON = "merchants_stale_reason";

    private final MetaRepository repo;

    public MetaController(MetaRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/meta")
    public MetaResult meta() {
        return new MetaResult(repo.get(KEY_MERCHANTS_COLLECTED_ON),
                repo.get(KEY_MERCHANTS_STALE_SINCE),
                repo.get(KEY_MERCHANTS_STALE_REASON));
    }
}
