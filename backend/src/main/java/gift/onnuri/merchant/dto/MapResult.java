package gift.onnuri.merchant.dto;

import java.util.List;

/** 지도용 좌표 목록. 상한 초과 시 truncated=true, 마커 생략(프론트가 안내 표시). */
public record MapResult(List<MapPin> pins, long total, boolean truncated) {
    public record MapPin(String id, String name, String cat, String brand,
                         String addr, String market, String card, String qr, Double lat, Double lng) {}
}
