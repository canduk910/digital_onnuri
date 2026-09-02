package gift.onnuri.online.probe;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 저장소의 경계 조건. SQL 을 만들기 전에 되돌려보내야 하는 경우들이다. */
class OnlineProductIndexRepositoryTest {

    private final JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    private final OnlineProductIndexRepository repo = new OnlineProductIndexRepository(jdbc);

    @Test
    void 대상_몰이_없으면_질의하지_않는다() {
        // IN () 는 문법 오류다. 조회 대상이 전부 실시간 몰인 경우 실제로 빈 목록이 온다.
        assertTrue(repo.summarize(List.of()).isEmpty());
        assertTrue(repo.findMatching(List.of(), List.of("김치")).isEmpty());
        Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void 낱말이_없으면_질의하지_않는다() {
        assertTrue(repo.findMatching(List.of("genius-mall"), List.of()).isEmpty());
        Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void LIKE_메타문자는_값으로_취급한다() {
        // 검색어의 %·_ 가 와일드카드가 되면 엉뚱한 상품명이 걸려 "찾았다"가 거짓이 된다.
        assertEquals("100\\%\\_국내산", OnlineProductIndexRepository.escapeLike("100%_국내산"));
        assertEquals("a\\\\b", OnlineProductIndexRepository.escapeLike("a\\b"));
        assertEquals("김치", OnlineProductIndexRepository.escapeLike("김치"));
    }
}
