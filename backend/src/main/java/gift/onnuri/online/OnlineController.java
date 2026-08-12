package gift.onnuri.online;

import org.springframework.web.bind.annotation.*;

/**
 * 온라인 사용처 플랫폼 목록 API(2026-08-12 이관). online.html·terms.html이 소비.
 * GET+POST 병행(기존 컨트롤러 관례) — 필터가 없어 둘은 동일 응답, POST는 프론트 착지·본문 컨벤션 통일용.
 */
@RestController
@RequestMapping("/api/online")
public class OnlineController {

    private final OnlineService svc;

    public OnlineController(OnlineService svc) {
        this.svc = svc;
    }

    @GetMapping("/platforms")
    public OnlineResult platformsGet() {
        return svc.platforms();
    }

    @PostMapping("/platforms")
    public OnlineResult platformsPost() {
        return svc.platforms();
    }
}
