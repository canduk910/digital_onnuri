package gift.onnuri.online.probe;

import java.net.URI;
import java.net.URLEncoder;

/** 몰별 검색 URL 조립. {q} 를 해당 몰 charset 으로 인코딩해 끼운다. */
public final class ProbeUrl {

    public static URI build(ProbeTarget t, ProbeQuery q) {
        String encoded = URLEncoder.encode(q.normalized(), t.charset());
        return URI.create(t.searchUrlTemplate().replace("{q}", encoded));
    }

    private ProbeUrl() {}
}
