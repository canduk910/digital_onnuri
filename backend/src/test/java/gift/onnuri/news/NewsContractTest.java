package gift.onnuri.news;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /api/news 계약 + RSS 파싱 규칙. news.html이 이 키를 그대로 소비한다 —
 * 이름이 바뀌면 뉴스 목록이 에러 없이 조용히 비어 보인다.
 */
class NewsContractTest {

    private List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
    }

    @Test
    void 응답_record_컴포넌트명_고정() {
        assertEquals(List.of("fetchedAt", "items"), components(NewsResult.class));
        assertEquals(List.of("title", "link", "source", "pubDate"), components(NewsItem.class));
    }

    @Test
    void RSS_아이템을_파싱하고_제목의_출처_접미를_제거한다() {
        String xml = "<rss><channel>"
                + "<item><title>온누리상품권 할인 확대 - 한국경제</title>"
                + "<link>https://news.example/1</link>"
                + "<pubDate>Tue, 09 Dec 2025 04:00:00 GMT</pubDate>"
                + "<source url=\"https://hankyung.com\">한국경제</source></item>"
                + "<item><title>제목만 있는 기사</title><link>https://news.example/2</link></item>"
                + "</channel></rss>";
        List<NewsItem> items = NewsService.parse(xml);
        assertEquals(2, items.size());
        assertEquals("온누리상품권 할인 확대", items.get(0).title());
        assertEquals("한국경제", items.get(0).source());
        assertEquals("2025-12-09 13:00", items.get(0).pubDate());   // GMT→KST(+9)
        assertEquals("제목만 있는 기사", items.get(1).title());
        assertNull(items.get(1).pubDate());
    }

    @Test
    void 접미_제거는_짧은_제목을_자르지_않는다() {
        assertEquals("온누리 - 안내", NewsService.stripSourceSuffix("온누리 - 안내", null));
        assertEquals("온누리상품권 사용처 대폭 확대", NewsService.stripSourceSuffix("온누리상품권 사용처 대폭 확대 - 어딘가일보", null));
    }
}
