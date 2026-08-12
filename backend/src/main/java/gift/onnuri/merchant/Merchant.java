package gift.onnuri.merchant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 가맹점 정본 엔티티. data/merchants/*.json 필드와 1:1 매핑. */
@Entity
@Table(name = "merchant")
@Getter
@Setter
@NoArgsConstructor
public class Merchant {

    @Id
    private String id;                 // frCd

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String cat;                // 업종 대분류

    private String brand;              // 자동탐지 브랜드명(nullable)

    @Column(nullable = false)
    private String region;             // 서울/인천/경기

    private String si;                 // 경기 시
    private String gu;                 // 구
    private String dong;               // 법정동

    private String addr;
    private String market;

    @Column(name = "market_type")
    private String marketType;

    private String paper;              // Y/N
    private String card;               // Y/N
    private String qr;                 // Y/N

    private Double lat;
    private Double lng;
}
