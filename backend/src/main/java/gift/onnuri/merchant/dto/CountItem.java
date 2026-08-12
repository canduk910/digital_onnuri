package gift.onnuri.merchant.dto;

/** 집계 항목(업종별·브랜드별 카운트, 지역 옵션 등). */
public record CountItem(String key, long count) {}
