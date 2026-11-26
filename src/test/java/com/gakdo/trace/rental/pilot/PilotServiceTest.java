// 이 파일이 확인하는 것: 파일럿 참가자 등록 규칙(DB 없이).

package com.gakdo.trace.rental.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.infra.DeviceRepository;
import com.gakdo.trace.rental.pilot.application.PilotService;
import com.gakdo.trace.rental.pilot.domain.PilotParticipant;
import com.gakdo.trace.rental.pilot.infra.PilotParticipantRepository;
import com.gakdo.trace.rental.support.Fixtures;

class PilotServiceTest {

    // (도구) mock(클래스) = 진짜 DB 대신 쓰는 가짜 객체(목)를 만든다. 시키지 않은 질문엔 빈 값(null·false·Optional.empty)을 돌려준다
    // 준비: 참가자 저장소를 가짜로 만든다 (DB 없이 테스트하려고)
    private final PilotParticipantRepository participants = mock(PilotParticipantRepository.class);
    // 준비: 기기 저장소도 가짜로 만든다
    private final DeviceRepository devices = mock(DeviceRepository.class);
    // 준비: 시험할 대상인 파일럿 서비스를 위 가짜 저장소 두 개로 만든다
    private final PilotService service = new PilotService(participants, devices);
    // 준비: 등록에 쓸 테스트 기기(시리얼 C02PILOT0001)를 만든다
    private final Device device = Fixtures.device("C02PILOT0001");
    // 준비: 참가자가 동의한 시각을 한국 시간 11/5 14:00으로 고정한다
    private final OffsetDateTime consent = OffsetDateTime.parse("2026-11-05T14:00:00+09:00");

    // [상황] 별칭 P01·기기·한국 시간 동의 시각으로 등록한다.
    // [기대] 별칭과 기기 ID만 저장하고, 동의 시각은 UTC로 바꿔 둔다. 참가자 ID는 21자 공개 ID.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("등록하면 별칭과 기기 ID만 저장하고 동의 시각은 UTC로 둔다")
    void registers() {
        // (도구) when(가짜.메서드(값)).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것
        // 준비: 기기 저장소가 이 기기 ID로 찾으면 위 테스트 기기를 돌려주게 한다
        when(devices.findWithModelByPublicId(device.getPublicId())).thenReturn(Optional.of(device));
        // (도구) any() = 어떤 값이 와도 맞다고 보는 조건. thenAnswer = 결과를 계산해서 돌려줌(여기선 받은 첫 번째 값을 그대로)
        // 준비: 참가자를 저장하면 저장하려던 객체를 그대로 돌려주게 한다 (DB 저장을 흉내)
        when(participants.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        // 실행: 별칭 P01, 기기 ID, 동의 시각으로 참가자를 등록한다
        PilotService.Registered r = service.register("P01", device.getPublicId(), consent);

        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사. 다르면 테스트 실패
        // 확인: 등록 결과의 별칭이 P01이어야 한다
        assertThat(r.alias()).isEqualTo("P01");
        // 확인: 등록 결과의 기기 ID가 보낸 기기 ID와 같아야 한다
        assertThat(r.deviceId()).isEqualTo(device.getPublicId());
        // 확인: 동의 시각은 같은 순간을 UTC(+00:00) 시간대로 바꾼 값이어야 한다 (한국 14:00 → UTC 05:00)
        assertThat(r.consentAt()).isEqualTo(consent.toInstant().atOffset(java.time.ZoneOffset.UTC));
        // 확인: 참가자 ID는 영문·숫자·_·- 로 된 21자여야 한다 (NanoID 공개 ID 규칙, matches = 정규식에 맞는지)
        assertThat(r.participantId()).matches("^[A-Za-z0-9_-]{21}$");
    }

    // [상황] 이미 쓰는 별칭, 없는 기기, 내일 날짜의 동의 시각으로 등록한다.
    // [기대] 각각 409·404·400이고 아무것도 저장하지 않는다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등), 404 = 찾을 수 없음(없는 주소나 데이터),
    //          400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("이미 쓰는 별칭은 409, 없는 기기는 404, 미래 동의 시각은 400이다")
    void rejects() {
        // 준비: 기기 저장소가 이 기기 ID로 찾으면 테스트 기기를 돌려주게 한다
        when(devices.findWithModelByPublicId(device.getPublicId())).thenReturn(Optional.of(device));
        // 준비: 별칭 P01은 "이미 있다(true)"고 답하게 한다
        when(participants.existsByAlias("P01")).thenReturn(true);
        // (도구) assertThatThrownBy(코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행+확인: 이미 있는 별칭 P01로 등록하면 예외가 나야 한다
        assertThatThrownBy(() -> service.register("P01", device.getPublicId(), consent))
                // 확인: 예외의 에러 코드가 PILOT_ALIAS_EXISTS(409, 별칭 중복)여야 한다
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.PILOT_ALIAS_EXISTS);
        // 실행+확인: 없는 기기 ID(zzz…)로 등록하면 예외가 나야 한다 (가짜 저장소는 모르는 ID에 빈 값을 돌려줌)
        assertThatThrownBy(() -> service.register("P02", "zzzzzzzzzzzzzzzzzzzzz", consent))
                // 확인: 에러 코드가 NOT_FOUND(404, 기기 없음)여야 한다
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
        // 실행+확인: 동의 시각을 내일(미래)로 넣어 등록하면 예외가 나야 한다
        assertThatThrownBy(() -> service.register("P02", device.getPublicId(), OffsetDateTime.now().plusDays(1)))
                // 확인: 에러 코드가 VALIDATION_FAILED(400, 입력값 오류)여야 한다
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        // (도구) verify(가짜, never()).메서드(...) = "가짜의 이 메서드가 한 번도 불리지 않았어야 한다"는 검사
        // 확인: 세 번 모두 거절됐으니 참가자 저장(saveAndFlush)은 한 번도 불리지 않았어야 한다 (잘못된 데이터가 저장되지 않음)
        verify(participants, never()).saveAndFlush(any(PilotParticipant.class));
    }
}
