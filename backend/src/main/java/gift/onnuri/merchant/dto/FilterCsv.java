package gift.onnuri.merchant.dto;

import java.util.Arrays;
import java.util.List;

/**
 * 다중 필터 값 파싱(2026-08-12): "A,B" → [A, B]. null/빈값/"전체" → null(필터 없음).
 * MerchantSpecs·ClusterRepository가 공유 — 프론트 JSON 폴백도 같은 규칙(경계면, FilterCsvTest).
 */
public final class FilterCsv {

    private FilterCsv() {
    }

    public static List<String> parse(String s) {
        if (s == null) return null;
        List<String> out = Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty() && !"전체".equals(v))
                .toList();
        return out.isEmpty() ? null : out;
    }
}
