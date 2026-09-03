package gift.onnuri.online.probe;

import java.util.List;

/**
 * 전일 색인에서 걸린 몰 하나 (ADR-18).
 *
 * ProbeHit 과 **일부러 다른 record 로 둔다.** 실시간 층이 하는 말은 "지금 검색된다"이고
 * 이 층이 하는 말은 "어제 이 이름의 상품이 올라와 있었다"다. 두 주장을 같은 그릇에 담으면
 * 화면 문구가 둘 중 하나에 대해 거짓이 된다.
 *
 * name 은 **몰 이름**이다(ProbeHit 과 같은 자리). 상품명은 sampleTitles 에 들어간다.
 */
public record IndexHit(
        String platformId,
        String name,              // 몰 이름
        int matchCount,           // 검색어의 **모든** 낱말을 담은 상품명 수. 일부만 맞으면 0.
        List<String> sampleTitles,
        boolean samplePartial,    // true = 전부 담은 이름이 없어 일부 낱말만 맞는 이름을 보여준다
        String searchUrl,
        String collectedOn,       // 이 몰 색인의 수집일
        /**
         * sampleTitles 와 **같은 순서**의 상품 주소(2026-09-04 신설). 비어 있을 수 있다.
         *
         * 왜 필요한가: 색인 대상은 정의상 '검색이 안 되는 몰'이라 searchUrl 은 몰 홈이고,
         * 이용자가 그 화면에서 우리가 보여 준 상품을 찾을 방법이 없었다. 배치가 이미
         * 상품에 닿는 주소를 색인에 넣어 두므로 그것을 그대로 실어 보낸다.
         */
        List<String> sampleUrls
) {}
