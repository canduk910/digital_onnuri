package gift.onnuri.merchant.dto;

import java.util.List;

/** 페이징 결과. */
public record PageResult<T>(List<T> items, long total, int page, int size) {}
