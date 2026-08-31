package gift.onnuri.online.probe;

/** POST 본문. 검색 조건을 주소창에 노출하지 않는 기존 관례(ADR-13)를 따른다. */
public record ProbeRequest(String q) {}
