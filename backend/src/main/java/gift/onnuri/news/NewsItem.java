package gift.onnuri.news;

/**
 * 뉴스 1건 — 제목·링크·출처·게시시각만 담는다(본문 미수록 — 저작권·정확성 책임은 각 언론사).
 * pubDate는 "yyyy-MM-dd HH:mm"(KST) 문자열로 정규화해 프론트가 그대로 표시한다.
 */
public record NewsItem(String title, String link, String source, String pubDate) {
}
