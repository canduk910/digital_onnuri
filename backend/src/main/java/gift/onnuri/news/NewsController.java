package gift.onnuri.news;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/news — '온누리상품권' 최신 뉴스(제목·링크·출처·시각). news.html이 소비한다. */
@RestController
@RequestMapping("/api")
public class NewsController {

    private final NewsService svc;

    public NewsController(NewsService svc) {
        this.svc = svc;
    }

    @GetMapping("/news")
    public NewsResult news() {
        return svc.latest();
    }
}
