package com.shop.shop.support;

import com.shop.shop.inventory.repository.StockLedgerRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 풀 {@code @SpringBootTest} 컨텍스트(DB 미기동, 전 repo @MockitoBean 패턴)에서
 * <b>애플리케이션 빈 그래프 충족만을 위해 필요한 Repository</b>를 한 곳에서 mock 등록하는 공용 합성 애노테이션.
 *
 * <p>배경: 이 프로젝트의 보안/뷰 컨트롤러 테스트는 {@code @SpringBootTest + @ActiveProfiles("test")}로
 * 풀 컨텍스트를 띄우되 실 DB 없이 모든 Repository를 {@code @MockitoBean}으로 주입한다. 핵심 서비스에
 * Repository 의존이 새로 추가되면(예: Task 034의 {@code StockLedgerRepository}) 이를 mock하지 않은
 * 수십 개 테스트의 컨텍스트 로드가 일제히 깨진다.
 *
 * <p>해결: 그런 "배선 충족용(개별 테스트가 stub하지 않는)" Repository를 <b>여기 한 곳</b>에 모은다.
 * 클래스 레벨 {@code @MockitoBean(types = ...)}(Spring Framework 6.2+)을 메타 애노테이션으로 합성한다.
 * 앞으로 동종 Repository가 추가되면 <b>이 애노테이션의 {@code types}만</b> 갱신하면 되고, 개별 테스트는 무수정이다.
 *
 * <p>주의: 특정 Repository를 <b>stub</b>해야 하는 테스트는 그 타입을 여기 넣지 말고 종전대로
 * 필드 {@code @MockitoBean}으로 선언한다(같은 타입 이중 override 충돌 방지).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@MockitoBean(types = {
        StockLedgerRepository.class
})
public @interface MockSharedRepositories {
}
