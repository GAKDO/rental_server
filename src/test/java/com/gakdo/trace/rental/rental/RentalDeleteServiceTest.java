// 이 파일이 확인하는 것: 시험 렌탈 지우기 규칙. 일련번호가 TEST-로 시작하는 렌탈만 지울 수 있다.

package com.gakdo.trace.rental.rental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.device.infra.DeviceRepository;
import com.gakdo.trace.rental.rental.application.CheckpointEventPublisher;
import com.gakdo.trace.rental.rental.application.CheckpointPurgedEvent;
import com.gakdo.trace.rental.rental.application.RentalService;
import com.gakdo.trace.rental.rental.domain.CheckpointType;
import com.gakdo.trace.rental.rental.domain.Rental;
import com.gakdo.trace.rental.rental.infra.CheckpointRepository;
import com.gakdo.trace.rental.rental.infra.RentalRepository;
import com.gakdo.trace.rental.support.Fixtures;

class RentalDeleteServiceTest {

    // (도구) mock(클래스) = 진짜 DB 대신 쓰는 가짜 객체(목). 시키지 않은 질문엔 빈 값을 돌려주고, 불린 기록을 남긴다
    // 준비: 렌탈 저장소를 가짜로 만든다
    private final RentalRepository rentals = mock(RentalRepository.class);
    // 준비: 시점 저장소를 가짜로 만든다
    private final CheckpointRepository checkpoints = mock(CheckpointRepository.class);
    // 준비: 기기 저장소를 가짜로 만든다
    private final DeviceRepository devices = mock(DeviceRepository.class);
    // 준비: 이벤트 보내는 곳(RabbitMQ)도 가짜로 만든다 (보낸 이벤트를 확인하려고)
    private final CheckpointEventPublisher events = mock(CheckpointEventPublisher.class);
    // 시험할 대상인 렌탈 서비스 (테스트마다 새로 만든다)
    private RentalService service;

    // (도구) @BeforeEach = 각 테스트가 시작되기 직전마다 실행한다 (테스트끼리 영향을 주지 않게)
    @BeforeEach
    void setUp() {
        // 준비: 가짜 저장소·가짜 이벤트 발행기로 렌탈 서비스를 새로 만든다
        service = new RentalService(rentals, checkpoints, devices, events);
    }

    // [상황] TEST-0002 기기의 시험 렌탈(출고 시점 1개)을 지운다.
    // [기대] 시점·렌탈·기기를 지우고, 시점마다 "시험 시점 지움" 이벤트를 보내 다른 서버도 지우게 한다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("시험 렌탈을 지우면 시점·렌탈·기기를 지우고 시점마다 trace.checkpoint.purged를 낸다")
    void deletesTestRentalAndPublishesPerCheckpoint() {
        // 준비: 시리얼이 TEST-로 시작하는 시험 기기로 렌탈을 만든다
        Rental rental = Rental.open(Fixtures.device("TEST-0002"), "TEST-SAMPLE");
        // 준비: 출고 시점을 시작하고 그 시점 ID를 기억해 둔다 (나중에 이벤트에 들어갔는지 보려고)
        String out = rental.startCheckpoint(CheckpointType.OUT).getPublicId();
        // (도구) when(가짜.메서드(값)).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것
        // 준비: 렌탈 저장소가 이 렌탈 ID로 (잠그고) 찾으면 위 렌탈을 돌려주게 한다
        when(rentals.lockByPublicId(rental.getPublicId())).thenReturn(Optional.of(rental));
        // 준비: 이 기기에 다른 렌탈이 남아 있냐고 물으면 "없다(false)"고 답하게 한다 (any() = 아무 값이나)
        when(rentals.existsByDevice_Id(any())).thenReturn(false);
        // 준비: 이 기기에 파일럿 참가자가 묶여 있냐고 물으면 "없다"고 답하게 한다
        when(devices.hasPilotParticipant(any())).thenReturn(false);

        // 실행: 시험 렌탈을 지운다
        service.deleteTestRental(rental.getPublicId());

        // (도구) verify(가짜).메서드(...) = 가짜의 그 메서드가 (한 번) 불렸어야 한다는 검사
        // 확인: 그 렌탈의 시점들을 지우는 메서드가 불렸어야 한다
        verify(checkpoints).deleteByRentalId(any());
        // 확인: 렌탈 행을 지우는 메서드가 불렸어야 한다
        verify(rentals).deleteRow(any());
        // 확인: 다른 렌탈·파일럿이 없으니 기기 행도 지워졌어야 한다
        verify(devices).deleteRow(any());
        // (도구) ArgumentCaptor = 가짜 메서드에 실제로 넘어간 값을 붙잡아 두는 도구 (나중에 내용 검사용)
        // 준비: 보낸 "시점 지움" 이벤트를 붙잡을 그릇을 만든다
        ArgumentCaptor<CheckpointPurgedEvent> sent = ArgumentCaptor.forClass(CheckpointPurgedEvent.class);
        // 확인: 시점이 1개였으니 이벤트 발행이 정확히 1번(times(1)) 불렸어야 하고, 넘긴 이벤트를 붙잡는다
        verify(events, times(1)).publishPurged(sent.capture());
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 이벤트의 시점 ID가 지운 출고 시점 ID여야 한다
        assertThat(sent.getValue().checkpointId()).isEqualTo(out);
        // 확인: 이벤트의 렌탈 ID가 지운 렌탈 ID여야 한다
        assertThat(sent.getValue().rentalId()).isEqualTo(rental.getPublicId());
        // 확인: 이벤트의 시점 종류가 "OUT"(출고)이어야 한다
        assertThat(sent.getValue().checkpointType()).isEqualTo("OUT");
        // 확인: 이벤트의 기기 시리얼이 TEST-0002여야 한다 (받는 서버가 시험 데이터인지 다시 확인할 수 있게)
        assertThat(sent.getValue().serial()).isEqualTo("TEST-0002");
        // 확인: 이벤트 형식 버전이 계약대로 1.0이어야 한다
        assertThat(sent.getValue().schemaVersion()).isEqualTo("1.0");
    }

    // [상황] 그 기기에 다른 렌탈이 남아 있다.
    // [기대] 렌탈만 지우고 기기는 남긴다.
    @Test
    @DisplayName("기기에 다른 렌탈이 남아 있으면 기기는 지우지 않는다")
    void keepsDeviceWithOtherRentals() {
        // 준비: 시험 기기 TEST-0003으로 렌탈을 만든다 (시점은 시작하지 않음)
        Rental rental = Rental.open(Fixtures.device("TEST-0003"), "TEST-SAMPLE");
        // 준비: 렌탈 저장소가 이 렌탈 ID로 찾으면 위 렌탈을 돌려주게 한다
        when(rentals.lockByPublicId(rental.getPublicId())).thenReturn(Optional.of(rental));
        // 준비: 이 기기에 다른 렌탈이 "남아 있다(true)"고 답하게 한다
        when(rentals.existsByDevice_Id(any())).thenReturn(true);

        // 실행: 시험 렌탈을 지운다
        service.deleteTestRental(rental.getPublicId());

        // 확인: 렌탈 행은 지워졌어야 한다
        verify(rentals).deleteRow(any());
        // (도구) never() = 한 번도 불리지 않았어야 한다는 조건
        // 확인: 다른 렌탈이 남아 있으니 기기 행은 지우지 않았어야 한다
        verify(devices, never()).deleteRow(any());
        // 확인: 이 렌탈엔 시점이 없었으니 "시점 지움" 이벤트도 보내지 않았어야 한다
        verify(events, never()).publishPurged(any());
    }

    // [상황] 실제 기기(C02...)의 렌탈을 지운다.
    // [기대] 409 NOT_TEST_DATA, 아무것도 지우지 않는다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("시리얼이 TEST-로 시작하지 않는 렌탈은 409 NOT_TEST_DATA이고 아무것도 지우지 않는다")
    void refusesRealRental() {
        // 준비: 실제 기기처럼 시리얼이 TEST-로 시작하지 않는 기기로 렌탈을 만든다
        Rental rental = Rental.open(Fixtures.device("C02REAL0001"), "R-2026-0012");
        // 준비: 렌탈 저장소가 이 렌탈 ID로 찾으면 위 렌탈을 돌려주게 한다
        when(rentals.lockByPublicId(rental.getPublicId())).thenReturn(Optional.of(rental));

        // 준비: 지울 렌탈 ID를 변수에 담아 둔다
        String rentalId = rental.getPublicId();
        // (도구) assertThatThrownBy(코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행+확인: 실제 렌탈을 지우려 하면 예외가 나야 한다
        assertThatThrownBy(() -> service.deleteTestRental(rentalId))
                // 확인: 예외 종류는 DomainException이어야 한다
                .isInstanceOf(DomainException.class)
                // 확인: 에러 코드가 NOT_TEST_DATA(시험 데이터 아님, 409)여야 한다
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.NOT_TEST_DATA);
        // 확인: 시점은 하나도 지우지 않았어야 한다 (실제 기록 보호)
        verify(checkpoints, never()).deleteByRentalId(any());
        // 확인: 렌탈 행도 지우지 않았어야 한다
        verify(rentals, never()).deleteRow(any());
        // 확인: 다른 서버에 지우라는 이벤트도 보내지 않았어야 한다
        verify(events, never()).publishPurged(any());
    }

    // [상황] 없는 렌탈을 지운다.
    // [기대] 404.
    //        ※ 응답 코드: 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("없는 렌탈은 404")
    void missingRental() {
        // 준비: 렌탈 저장소가 이 ID로 찾으면 "없음(Optional.empty)"을 돌려주게 한다
        when(rentals.lockByPublicId("Ws0HLv6QRbDILPWOcz5I2")).thenReturn(Optional.empty());
        // 실행+확인: 없는 렌탈을 지우려 하면 예외가 나야 한다
        assertThatThrownBy(() -> service.deleteTestRental("Ws0HLv6QRbDILPWOcz5I2"))
                // 확인: 예외 종류는 DomainException이어야 한다
                .isInstanceOf(DomainException.class)
                // 확인: 에러 코드가 NOT_FOUND(404, 찾을 수 없음)여야 한다
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
    }
}
