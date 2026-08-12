package gift.onnuri.merchant.dto;

import gift.onnuri.merchant.Merchant;

/** 리스트·상세용 응답 뷰. */
public record MerchantView(
        String id, String name, String cat, String brand,
        String region, String si, String gu, String dong,
        String addr, String market, String marketType,
        String paper, String card, String qr,
        Double lat, Double lng
) {
    public static MerchantView of(Merchant m) {
        return new MerchantView(
                m.getId(), m.getName(), m.getCat(), m.getBrand(),
                m.getRegion(), m.getSi(), m.getGu(), m.getDong(),
                m.getAddr(), m.getMarket(), m.getMarketType(),
                m.getPaper(), m.getCard(), m.getQr(),
                m.getLat(), m.getLng());
    }
}
