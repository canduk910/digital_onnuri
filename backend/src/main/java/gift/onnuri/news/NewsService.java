package gift.onnuri.news;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * '온누리상품권' 최신 뉴스 — 구글 뉴스 공개 RSS(키 불필요)를 서버가 수집·캐시해 프론트에 준다.
 * (프론트 직접 호출은 CORS로 불가 + 원천 부하를 서버 캐시 1곳으로 묶는다.)
 *
 * - 캐시 TTL 30분. 수집 실패 시 마지막 성공 캐시를 그대로 반환(fail-open — 뉴스는 보조 기능).
 * - 제목·링크·출처·시각만 취급(본문 미수록). 구글 RSS 제목의 " - 출처" 접미는 중복이라 제거.
 */
@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);
    private static final String RSS_URL =
            "https://news.google.com/rss/search?q=%EC%98%A8%EB%88%84%EB%A6%AC%EC%83%81%ED%92%88%EA%B6%8C&hl=ko&gl=KR&ceid=KR:ko";
    private static final int MAX_ITEMS = 30;
    private static final long TTL_MS = 30 * 60 * 1000L;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link>(.*?)</link>", Pattern.DOTALL);
    private static final Pattern PUB = Pattern.compile("<pubDate>(.*?)</pubDate>");
    private static final Pattern SOURCE = Pattern.compile("<source[^>]*>(.*?)</source>");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private volatile NewsResult cache = new NewsResult(null, List.of());
    private volatile long cachedAt = 0;

    public NewsResult latest() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < TTL_MS && !cache.items().isEmpty()) return cache;
        synchronized (this) {
            if (System.currentTimeMillis() - cachedAt < TTL_MS && !cache.items().isEmpty()) return cache;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(RSS_URL))
                        .timeout(Duration.ofSeconds(8))
                        .header("User-Agent", "Mozilla/5.0 (onnuri-guide news)")
                        .GET().build();
                String xml = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
                List<NewsItem> items = parse(xml);
                if (!items.isEmpty()) {
                    cache = new NewsResult(ZonedDateTime.now(KST).format(OUT), items);
                    cachedAt = System.currentTimeMillis();
                }
            } catch (Exception e) {
                log.warn("뉴스 수집 실패 — 캐시 유지: {}", e.toString());
                cachedAt = System.currentTimeMillis() - TTL_MS + 60_000; // 1분 뒤 재시도
            }
            return cache;
        }
    }

    /**
     * RSS 를 항목으로 쪼개고 **최신순으로 정렬한 뒤** 상한만큼 자른다.
     *
     * 2026-09-04: 종전에는 받은 순서 그대로 앞에서 잘랐다. 구글 뉴스 RSS 는 기본이 관련도순이라
     * 화면이 `최신순` 이라 적어 두고 실제로는 관련도순을 보여 주고 있었다(실측: 09-03 08:00 다음에
     * 더 오래된 항목이 왔다). **자르기 전에** 정렬해야 한다 — 앞에서 자른 뒤 정렬하면
     * 최신 기사가 상한 밖에 있을 때 영영 안 나온다.
     *
     * pubDate 는 `yyyy-MM-dd HH:mm`(KST) 문자열이라 사전순 = 시간순이다. 날짜를 못 읽은 항목은
     * 뒤로 보낸다 — 날짜가 없다고 최신인 척하면 안 된다.
     */
    static List<NewsItem> parse(String xml) {
        List<NewsItem> all = new ArrayList<>();
        Matcher m = ITEM.matcher(xml == null ? "" : xml);
        while (m.find()) {
            String block = m.group(1);
            String title = unescape(group(TITLE, block));
            String link = unescape(group(LINK, block));
            String source = unescape(group(SOURCE, block));
            String pub = toKst(group(PUB, block));
            if (title == null || link == null) continue;
            all.add(new NewsItem(stripSourceSuffix(title, source), link, source, pub));
        }
        all.sort((a, b) -> {
            String x = a.pubDate(), y = b.pubDate();
            if (x == null && y == null) return 0;
            if (x == null) return 1;      // 날짜 없는 항목은 뒤로
            if (y == null) return -1;
            return y.compareTo(x);        // 최신 먼저
        });
        return all.size() > MAX_ITEMS ? new ArrayList<>(all.subList(0, MAX_ITEMS)) : all;
    }

    /** 구글 RSS 제목의 " - 출처명" 접미 제거(출처는 별도 필드로 제공하므로 중복). */
    static String stripSourceSuffix(String title, String source) {
        if (title == null) return null;
        if (source != null && title.endsWith(" - " + source)) {
            return title.substring(0, title.length() - source.length() - 3);
        }
        int i = title.lastIndexOf(" - ");
        return i > 10 ? title.substring(0, i) : title;   // 접미가 짧은 제목을 자르지 않게 하한
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String unescape(String s) {
        if (s == null) return null;
        return s.replace("<![CDATA[", "").replace("]]>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").trim();
    }

    private static String toKst(String rfc1123) {
        if (rfc1123 == null) return null;
        try {
            return ZonedDateTime.parse(rfc1123, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(KST).format(OUT);
        } catch (Exception e) { return null; }
    }
}
