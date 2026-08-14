package gift.onnuri.news;

import java.util.List;

/** GET /api/news 응답. fetchedAt = 서버가 원천(구글 뉴스 RSS)에서 수집한 시각(KST, yyyy-MM-dd HH:mm). */
public record NewsResult(String fetchedAt, List<NewsItem> items) {
}
