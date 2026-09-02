package gift.onnuri.online.probe;

import java.net.URI;
import java.net.URLEncoder;

/**
 * 몰별 검색 URL 조립. {q} 를 해당 몰 charset 으로 인코딩해 끼운다.
 *
 * {qq} 는 **두 번** 인코딩한다 — 현대이지웰의 내부 검색 API 가 그렇게 받는다
 * (화면이 보내는 요청이 `searchTerm=%25EA%25B9%2580…` 였다). 한 번만 인코딩하면 0건이 온다.
 */
public final class ProbeUrl {

    public static URI build(ProbeTarget t, ProbeQuery q) {
        return URI.create(fill(t.searchUrlTemplate(), t, q));
    }

    /** 템플릿의 {q}/{qq} 를 채운다. URL 과 form body 가 같은 규칙을 쓴다. */
    public static String fill(String template, ProbeTarget t, ProbeQuery q) {
        String once = URLEncoder.encode(q.normalized(), t.charset());
        return template.replace("{qq}", URLEncoder.encode(once, t.charset()))
                       .replace("{q}", once);
    }

    private ProbeUrl() {}
}
