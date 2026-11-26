// 테스트 도구(테스트 자체는 아님): API 테스트가 운영과 같은 보안 규칙·오류 형식을 쓰게 하는 바탕.

package com.gakdo.trace.rental.support;

import org.springframework.context.annotation.Import;

import com.gakdo.trace.rental.common.api.ProblemAdvice;
import com.gakdo.trace.rental.common.infra.SecurityConfig;

/** WebMvc 시험이 운영과 같은 보안 규칙·오류 형식을 쓰게 한다. */
// (도구) @Import = 이 클래스를 물려받는 테스트에 보안 설정(SecurityConfig)과 오류 응답 변환기(ProblemAdvice)를 함께 띄우라는 표시
// 이렇게 해야 테스트에서도 운영처럼 "로그인 없으면 401", "에러는 Problem Details 형식"이 그대로 적용된다
@Import({SecurityConfig.class, ProblemAdvice.class})
public abstract class WebTestBase {
}
