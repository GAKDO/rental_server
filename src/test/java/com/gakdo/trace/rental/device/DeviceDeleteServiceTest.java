// 이 파일이 확인하는 것: 기기·기종 지우기 규칙(11/10 추가). 이력이 있는 실제 기기는 지우지 않는다(기록 보존).

package com.gakdo.trace.rental.device;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.device.application.DeviceService;
import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.infra.DeviceRepository;
import com.gakdo.trace.rental.devicemodel.application.DeviceModelService;
import com.gakdo.trace.rental.devicemodel.domain.DeviceModel;
import com.gakdo.trace.rental.devicemodel.infra.DeviceModelRepository;
import com.gakdo.trace.rental.rental.application.RentalService;
import com.gakdo.trace.rental.rental.infra.RentalRepository;
import com.gakdo.trace.rental.support.Fixtures;

/** 기기·기종 지우기(11/10). DB 없이 규칙만 본다. */
class DeviceDeleteServiceTest {

    // (도구) mock(클래스) = 진짜 DB 대신 쓰는 가짜 객체(목). 시키지 않은 질문엔 빈 값(null·false·0)을 돌려주고, 불린 기록을 남긴다
    // 준비: 기기 저장소를 가짜로 만든다
    private final DeviceRepository devices = mock(DeviceRepository.class);
    // 준비: 기종 저장소를 가짜로 만든다
    private final DeviceModelRepository models = mock(DeviceModelRepository.class);
    // 준비: 렌탈 저장소를 가짜로 만든다
    private final RentalRepository rentals = mock(RentalRepository.class);
    // 준비: 렌탈 서비스도 가짜로 만든다 (시험 기기의 렌탈 지우기를 불렀는지만 보려고)
    private final RentalService rentalService = mock(RentalService.class);
    // 시험할 대상 1: 기기 서비스 (테스트마다 새로 만든다)
    private DeviceService deviceService;
    // 시험할 대상 2: 기종 서비스
    private DeviceModelService modelService;

    // (도구) @BeforeEach = 각 테스트가 시작되기 직전마다 실행한다 (테스트끼리 영향을 주지 않게)
    @BeforeEach
    void setUp() {
        // 준비: 가짜 저장소들로 기기 서비스를 만든다
        deviceService = new DeviceService(devices, models, rentals, rentalService);
        // 준비: 가짜 저장소들로 기종 서비스를 만든다
        modelService = new DeviceModelService(models, devices);
    }

    // 도우미: 그 일련번호의 기기가 있는 것처럼 준비한다.
    private Device given(String serial) {
        // 받은 시리얼로 테스트 기기를 만든다
        Device device = Fixtures.device(serial);
        // (도구) when(가짜.메서드(값)).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것
        // 기기 저장소가 이 기기 ID로 (잠그고) 찾으면 그 기기를 돌려주게 한다
        when(devices.lockByPublicId(device.getPublicId())).thenReturn(Optional.of(device));
        // 만든 기기를 돌려준다
        return device;
    }

    // [상황] 렌탈·파일럿 이력이 없는 실제 기기를 지운다.
    // [기대] 지운다(잘못 등록한 기기 정리).
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("렌탈·파일럿 이력이 없는 실제 기기는 지운다")
    void deletesUnusedDevice() {
        // 준비: 실제 기기(TEST-로 시작하지 않는 시리얼)가 있는 것처럼 준비한다
        Device device = given("C02REAL0001");
        // 준비: 이 기기에 렌탈 이력이 "없다(false)"고 답하게 한다 (any() = 아무 값이나)
        when(rentals.existsByDevice_Id(any())).thenReturn(false);

        // 실행: 기기를 지운다
        deviceService.delete(device.getPublicId());

        // (도구) verify(가짜).메서드(...) = 가짜의 그 메서드가 불렸어야 한다는 검사
        // 확인: 기기 행을 지우는 메서드가 불렸어야 한다
        verify(devices).deleteRow(any());
        // (도구) never() = 한 번도 불리지 않았어야 한다는 조건
        // 확인: 렌탈이 없으니 렌탈 지우기는 부르지 않았어야 한다
        verify(rentalService, never()).purgeAllOf(any());
    }

    // [상황] 렌탈 이력이 있는 실제 기기를 지운다.
    // [기대] 409 DEVICE_IN_USE, 아무것도 지우지 않는다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("렌탈 이력이 있는 실제 기기는 409 DEVICE_IN_USE이고 아무것도 지우지 않는다")
    void refusesRealDeviceWithRentals() {
        // 준비: 실제 기기가 있는 것처럼 준비한다
        Device device = given("C02REAL0002");
        // 준비: 이 기기에 렌탈 이력이 "있다(true)"고 답하게 한다
        when(rentals.existsByDevice_Id(any())).thenReturn(true);

        // 준비: 지울 기기 ID를 변수에 담는다
        String id = device.getPublicId();
        // (도구) assertThatThrownBy(코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행+확인: 이력 있는 실제 기기를 지우려 하면 DomainException이 나야 한다
        assertThatThrownBy(() -> deviceService.delete(id)).isInstanceOf(DomainException.class)
                // 확인: 에러 코드가 DEVICE_IN_USE(사용 중인 기기, 409)여야 한다
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.DEVICE_IN_USE);
        // 확인: 기기 행은 지우지 않았어야 한다 (기록 보존)
        verify(devices, never()).deleteRow(any());
        // 확인: 렌탈도 지우지 않았어야 한다
        verify(rentalService, never()).purgeAllOf(any());
    }

    // [상황] TEST- 시험 기기에 렌탈이 있다.
    // [기대] 렌탈을 시험 렌탈 지우기처럼 함께 지우고 기기도 지운다.
    @Test
    @DisplayName("TEST- 시험 기기는 렌탈이 있어도 렌탈을 시험 렌탈 지우기처럼 함께 지우고 기기도 지운다")
    void deletesTestDeviceWithRentals() {
        // 준비: 시험 기기(TEST-로 시작)가 있는 것처럼 준비한다
        Device device = given("TEST-0003");
        // 준비: 이 기기에 렌탈이 "있다"고 답하게 한다
        when(rentals.existsByDevice_Id(any())).thenReturn(true);

        // 실행: 시험 기기를 지운다
        deviceService.delete(device.getPublicId());

        // 확인: 그 기기의 렌탈을 모두 지우는 메서드가 이 기기로 불렸어야 한다
        verify(rentalService).purgeAllOf(device);
        // 확인: 기기 행도 지웠어야 한다
        verify(devices).deleteRow(any());
    }

    // [상황] 파일럿(실사용 시범) 참가 기록이 있는 시험 기기를 지운다.
    // [기대] 409 DEVICE_IN_USE(참가 기록은 보존).
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("파일럿 참가 기록이 있는 기기는 시험 기기라도 409 DEVICE_IN_USE")
    void refusesPilotDevice() {
        // 준비: 시험 기기가 있는 것처럼 준비한다
        Device device = given("TEST-0004");
        // 준비: 이 기기에 파일럿 참가 기록이 "있다"고 답하게 한다
        when(devices.hasPilotParticipant(any())).thenReturn(true);

        // 준비: 지울 기기 ID를 변수에 담는다
        String id = device.getPublicId();
        // 실행+확인: 파일럿 기록이 있는 기기를 지우려 하면 DomainException이 나야 한다
        assertThatThrownBy(() -> deviceService.delete(id)).isInstanceOf(DomainException.class)
                // 확인: 시험 기기라도 에러 코드가 DEVICE_IN_USE(409)여야 한다 (참가 기록 보존)
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.DEVICE_IN_USE);
        // 확인: 렌탈은 지우지 않았어야 한다
        verify(rentalService, never()).purgeAllOf(any());
        // 확인: 기기 행도 지우지 않았어야 한다
        verify(devices, never()).deleteRow(any());
    }

    // [상황] 없는 기기를 지운다.
    // [기대] 404.
    //        ※ 응답 코드: 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("없는 기기를 지우면 404")
    void missingDevice() {
        // 준비: 기기 저장소가 이 ID로 찾으면 "없음"을 돌려주게 한다
        when(devices.lockByPublicId("Ws0HLv6QRbDILPWOcz5I2")).thenReturn(Optional.empty());
        // 실행+확인: 없는 기기를 지우려 하면 DomainException이 나야 한다
        assertThatThrownBy(() -> deviceService.delete("Ws0HLv6QRbDILPWOcz5I2")).isInstanceOf(DomainException.class)
                // 확인: 에러 코드가 NOT_FOUND(404)여야 한다
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    // [상황] 등록된 기기가 없는 기종을 지운다.
    // [기대] 지운다.
    @Test
    @DisplayName("기기가 없는 기종은 지운다")
    void deletesUnusedModel() {
        // 준비: 견본 기종(맥북 에어 13 M2)을 만든다
        DeviceModel model = Fixtures.model();
        // 준비: 기종 저장소가 기종 코드 TEST-MODEL로 찾으면 위 기종을 돌려주게 한다
        when(models.findByModelCode("TEST-MODEL")).thenReturn(Optional.of(model));
        // 준비: 이 기종으로 등록된 기기 수를 물으면 0대라고 답하게 한다
        when(devices.countByModel_Id(any())).thenReturn(0L);

        // 실행: 기종을 지운다
        modelService.delete("TEST-MODEL");

        // 확인: 기종 저장소의 지우기가 이 기종으로 불렸어야 한다
        verify(models).delete(model);
    }

    // [상황] 기기가 등록된 기종을 지운다.
    // [기대] 409 DEVICE_MODEL_IN_USE.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("기기가 등록된 기종은 409 DEVICE_MODEL_IN_USE이고 지우지 않는다")
    void refusesModelInUse() {
        // 준비: 견본 기종을 만든다
        DeviceModel model = Fixtures.model();
        // 준비: 기종 코드 MBA13-M2로 찾으면 위 기종을 돌려주게 한다
        when(models.findByModelCode("MBA13-M2")).thenReturn(Optional.of(model));
        // 준비: 이 기종으로 등록된 기기가 2대 있다고 답하게 한다
        when(devices.countByModel_Id(any())).thenReturn(2L);

        // 실행+확인: 기기가 있는 기종을 지우려 하면 DomainException이 나야 한다
        assertThatThrownBy(() -> modelService.delete("MBA13-M2")).isInstanceOf(DomainException.class)
                // 확인: 에러 코드가 DEVICE_MODEL_IN_USE(사용 중인 기종, 409)여야 한다
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.DEVICE_MODEL_IN_USE);
        // 확인: 기종 지우기는 한 번도 불리지 않았어야 한다
        verify(models, never()).delete(any(DeviceModel.class));
    }

    // [상황] "기기 없음" 확인 직후 다른 관리자가 그 기종으로 기기를 등록해 DB가 삭제를 막는다.
    // [기대] DB 오류를 그대로 보이지 않고 같은 409로 바꿔 알린다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("확인 뒤 기기가 동시에 등록돼 외래 키가 막으면 같은 409로 바꾼다")
    void modelRaceBecomesConflict() {
        // 준비: 견본 기종을 만든다
        DeviceModel model = Fixtures.model();
        // 준비: 기종 코드로 찾으면 위 기종을 돌려주게 한다 (기기 수는 정하지 않아 가짜가 기본값 0을 돌려줌 = 확인은 통과)
        when(models.findByModelCode("MBA13-M2")).thenReturn(Optional.of(model));
        // (도구) doThrow(예외).when(가짜).메서드() = 그 메서드가 불리면 이 예외를 던지게 정해 두는 것 (돌려줄 값이 없는 메서드에 씀)
        // 준비: 지운 내용을 DB에 반영(flush)할 때 외래 키 위반 오류가 나게 한다 = 그 사이 다른 관리자가 기기를 등록한 상황
        doThrow(new DataIntegrityViolationException("fk")).when(models).flush();

        // 실행+확인: 기종을 지우면 DB 오류가 그대로가 아니라 DomainException으로 바뀌어 나와야 한다
        assertThatThrownBy(() -> modelService.delete("MBA13-M2")).isInstanceOf(DomainException.class)
                // 확인: 에러 코드가 DEVICE_MODEL_IN_USE(409)여야 한다 (먼저 확인했을 때와 같은 응답)
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.DEVICE_MODEL_IN_USE);
    }

    // [상황] 없는 기종을 지운다.
    // [기대] 404.
    //        ※ 응답 코드: 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("없는 기종을 지우면 404")
    void missingModel() {
        // 준비: 기종 코드 NOPE로 찾으면 "없음"을 돌려주게 한다
        when(models.findByModelCode("NOPE")).thenReturn(Optional.empty());
        // 실행+확인: 없는 기종을 지우려 하면 DomainException이 나야 한다
        assertThatThrownBy(() -> modelService.delete("NOPE")).isInstanceOf(DomainException.class)
                // 확인: 에러 코드가 NOT_FOUND(404)여야 한다
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
    }
}
