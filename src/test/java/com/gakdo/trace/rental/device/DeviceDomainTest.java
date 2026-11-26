// 이 파일이 확인하는 것: 기기(노트북 한 대)의 상태 규칙. 상태: 사용 가능(ACTIVE)·수리 중(MAINTENANCE)·폐기(RETIRED).

package com.gakdo.trace.rental.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.domain.DeviceChanges;
import com.gakdo.trace.rental.device.domain.DeviceChanges.TextChange;
import com.gakdo.trace.rental.device.domain.DeviceOs;
import com.gakdo.trace.rental.device.domain.DeviceStatus;
import com.gakdo.trace.rental.devicemodel.domain.DeviceModel;
import com.gakdo.trace.rental.devicemodel.domain.Dimensions;
import com.gakdo.trace.rental.support.Fixtures;

class DeviceDomainTest {

    // JSON 글자를 읽을 변환기 (아래 parse 도우미에서 쓴다)
    private final ObjectMapper mapper = new ObjectMapper();

    // [상황] 기기를 등록한다.
    // [기대] 사용 가능 상태로 시작하고, 공개 ID와 등록한 관리자를 남긴다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("기기는 ACTIVE로 시작하고 publicId·변경자(updated_by)를 남긴다")
    void registerStartsActive() {
        // 준비+실행: 시리얼 C02EXAMPLE01로 테스트 기기를 등록한다 (Fixtures = 견본 기기를 만들어 주는 도우미)
        Device device = Fixtures.device("C02EXAMPLE01");
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사. 다르면 테스트 실패
        // 확인: 새 기기의 상태는 사용 가능(ACTIVE)이어야 한다
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.ACTIVE);
        // 확인: 사용 가능 기기이니 렌탈할 수 있어야(true) 한다
        assertThat(device.isRentable()).isTrue();
        // 확인: 외부에 보이는 기기 ID는 21자여야 한다 (NanoID 규칙)
        assertThat(device.getPublicId()).hasSize(21);
        // 확인: 마지막으로 바꾼 사람이 등록한 관리자 ID로 남아야 한다
        assertThat(device.getUpdatedBy()).isEqualTo("admin0000000000000001");
        // 확인: 폐기하지 않았으니 폐기 시각은 비어 있어야 한다
        assertThat(device.getRetiredAt()).isNull();
    }

    // [상황] 기기를 폐기했다가 수리 중으로 되돌린다.
    // [기대] 폐기하면 폐기 시각이 채워지고, 수리 중으로 되돌리면 지워진다.
    @Test
    @DisplayName("폐기하면 retiredAt이 채워지고, 수리 중으로 되돌리면 retiredAt이 비워진다")
    void retireAndRestoreToMaintenance() {
        // 준비: 테스트 기기를 등록한다
        Device device = Fixtures.device("C02EXAMPLE01");
        // 실행: 관리자 2가 기기 상태만 폐기(RETIRED)로 바꾼다 (DeviceChanges = 색·자산번호·OS·상태 순, null은 안 바꿈)
        device.change(new DeviceChanges(null, null, null, DeviceStatus.RETIRED), "admin0000000000000002");
        // 확인: 상태가 폐기여야 한다
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.RETIRED);
        // 확인: 폐기 시각이 채워져야 한다
        assertThat(device.getRetiredAt()).isNotNull();
        // 확인: 폐기 기기는 렌탈할 수 없어야 한다
        assertThat(device.isRentable()).isFalse();
        // 확인: 바꾼 사람이 관리자 2로 남아야 한다
        assertThat(device.getUpdatedBy()).isEqualTo("admin0000000000000002");

        // 실행: 관리자 3이 폐기 기기를 수리 중(MAINTENANCE)으로 되돌린다
        device.change(new DeviceChanges(null, null, null, DeviceStatus.MAINTENANCE), "admin0000000000000003");
        // 확인: 상태가 수리 중이어야 한다
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.MAINTENANCE);
        // 확인: 폐기가 취소됐으니 폐기 시각은 지워져야 한다
        assertThat(device.getRetiredAt()).isNull();
        // 확인: 바꾼 사람이 관리자 3으로 남아야 한다
        assertThat(device.getUpdatedBy()).isEqualTo("admin0000000000000003");

        // 실행: 수리 중 기기를 사용 가능으로 바꾼다 (수리 중 → 사용 가능은 허용)
        device.change(new DeviceChanges(null, null, null, DeviceStatus.ACTIVE), null);
        // 확인: 다시 렌탈할 수 있어야 한다
        assertThat(device.isRentable()).isTrue();
    }

    // [상황] 폐기 기기를 바로 사용 가능으로 바꾼다.
    // [기대] 409, 아무것도 바뀌지 않는다(수리 중을 거쳐야 함).
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("폐기 기기를 사용 가능으로 바로 바꾸면 409 DEVICE_STATUS_TRANSITION_INVALID이고 아무것도 바뀌지 않는다")
    void retiredToActiveRejected() {
        // 준비: 테스트 기기를 등록한다
        Device device = Fixtures.device("C02EXAMPLE01");
        // 준비: 관리자 2가 기기를 폐기한다
        device.change(new DeviceChanges(null, null, null, DeviceStatus.RETIRED), "admin0000000000000002");
        // 준비: 지금 폐기 시각을 기억해 둔다 (나중에 안 바뀌었는지 보려고)
        var retiredAt = device.getRetiredAt();

        // (도구) assertThatThrownBy(코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행+확인: 관리자 3이 색 지우기 + 바로 사용 가능(ACTIVE)으로 바꾸려 하면 예외가 나야 한다
        assertThatThrownBy(() -> device.change(
                new DeviceChanges(TextChange.clear(), null, null, DeviceStatus.ACTIVE), "admin0000000000000003"))
                // 확인: 예외 종류는 DomainException이고, 에러 코드가
                .isInstanceOfSatisfying(DomainException.class,
                        // DEVICE_STATUS_TRANSITION_INVALID(허용되지 않는 상태 변경, 409)여야 한다
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID));
        // 확인: 상태는 폐기 그대로여야 한다
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.RETIRED);
        // 확인: 폐기 시각도 그대로여야 한다
        assertThat(device.getRetiredAt()).isEqualTo(retiredAt);
        // 확인: 같이 보낸 색 지우기도 적용되지 않았어야 한다 (일부만 바뀌지 않고 전부 거절)
        assertThat(device.getColor()).isNotNull();
        // 확인: 바꾼 사람도 관리자 2 그대로여야 한다
        assertThat(device.getUpdatedBy()).isEqualTo("admin0000000000000002");
    }

    // [상황] 수리 중 기기로 렌탈을 만들 수 있는지 본다.
    // [기대] 렌탈할 수 없다.
    @Test
    @DisplayName("수리 중 기기는 렌탈할 수 없다")
    void maintenanceNotRentable() {
        // 준비: 테스트 기기를 등록한다
        Device device = Fixtures.device("C02EXAMPLE01");
        // 실행: 상태를 수리 중으로 바꾼다
        device.change(new DeviceChanges(null, null, null, DeviceStatus.MAINTENANCE), null);
        // 확인: 수리 중 기기는 렌탈할 수 없어야 한다
        assertThat(device.isRentable()).isFalse();
        // 확인: 폐기가 아니니 폐기 시각은 비어 있어야 한다
        assertThat(device.getRetiredAt()).isNull();
    }

    // [상황] 기기 정보를 일부만 고친다.
    // [기대] 보내지 않은 값은 그대로, null로 보낸 값은 지우고, 일련번호는 그대로다.
    @Test
    @DisplayName("PATCH: 생략한 값은 그대로, null은 지우고, 시리얼은 그대로다")
    void changeKeepsSerial() {
        // 준비: 테스트 기기를 등록한다
        Device device = Fixtures.device("C02EXAMPLE01");
        // 실행: 색은 지우고(clear), 자산번호는 A-0002로, OS는 윈도우로 바꾸고, 상태는 생략(null)한다
        device.change(new DeviceChanges(TextChange.clear(), TextChange.of("A-0002"), DeviceOs.WINDOWS, null), null);
        // 확인: 색이 지워져(null) 있어야 한다
        assertThat(device.getColor()).isNull();
        // 확인: 자산번호가 새 값이어야 한다
        assertThat(device.getAssetNo()).isEqualTo("A-0002");
        // 확인: OS가 윈도우여야 한다
        assertThat(device.getOs()).isEqualTo(DeviceOs.WINDOWS);
        // 확인: 시리얼은 바꿀 수 없으니 처음 값 그대로여야 한다
        assertThat(device.getSerial()).isEqualTo("C02EXAMPLE01");
        // 확인: 생략한 상태는 사용 가능 그대로여야 한다
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.ACTIVE);
    }

    // [상황] 수정 본문에 일련번호·기종 코드를 넣는다.
    // [기대] 400.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("시리얼은 등록 뒤 바꿀 수 없다: PATCH 본문에 serial·modelCode가 있으면 400")
    void serialImmutable() {
        // 실행+확인: 수정 본문에 serial·modelCode를 넣어 읽으면 예외가 나야 한다
        assertThatThrownBy(() -> parse("{\"serial\":\"X\",\"modelCode\":\"Y\"}"))
                // 확인: 예외 종류가 DomainException이고, 그 안을 아래처럼 검사한다
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    // 확인: 에러 코드가 VALIDATION_FAILED(400)여야 한다
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    // 확인: 칸별 오류 목록에서 칸 이름만 뽑아 보면
                    assertThat(e.errors()).extracting(DomainException.FieldError::field)
                            // serial과 modelCode 두 칸이 (순서 무관) 정확히 들어 있어야 한다
                            .containsExactlyInAnyOrder("serial", "modelCode");
                });
    }

    // [상황] 빈 본문, 모르는 값, 너무 긴 값.
    // [기대] 400.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("PATCH 본문 형식: 빈 본문·모르는 값·너무 긴 값은 400")
    void parseRejectsInvalid() {
        // 실행+확인: 빈 본문({})은 바꿀 게 없으니 예외가 나야 한다
        assertThatThrownBy(() -> parse("{}")).isInstanceOf(DomainException.class);
        // 실행+확인: 없는 상태 값(BROKEN)은 예외가 나야 한다
        assertThatThrownBy(() -> parse("{\"status\":\"BROKEN\"}")).isInstanceOf(DomainException.class);
        // 실행+확인: OS에 글자 대신 숫자를 넣으면 예외가 나야 한다
        assertThatThrownBy(() -> parse("{\"os\":1}")).isInstanceOf(DomainException.class);
        // 실행+확인: 색에 숫자를 넣어도 예외가 나야 한다
        assertThatThrownBy(() -> parse("{\"color\":123}")).isInstanceOf(DomainException.class);
        // 준비: 33자짜리 색 이름을 담은 본문을 만든다 (색은 32자까지)
        String tooLong = "{\"color\":\"" + "x".repeat(33) + "\"}";
        // 실행+확인: 너무 긴 색 이름은 예외가 나야 한다
        assertThatThrownBy(() -> parse(tooLong)).isInstanceOf(DomainException.class);
    }

    // [상황] 색상을 null로 보낸 경우와 아예 안 보낸 경우.
    // [기대] null은 지우기, 안 보내면 그대로.
    @Test
    @DisplayName("PATCH 본문: color null은 지우기, 생략은 그대로")
    void parseNullVsMissing() throws Exception {
        // 실행: 색은 null, 상태 MAINTENANCE, OS MACOS인 본문을 읽는다 (자산번호는 아예 안 보냄)
        DeviceChanges changes = parse("{\"color\":null,\"status\":\"MAINTENANCE\",\"os\":\"MACOS\"}");
        // 확인: null로 보낸 색은 "지우기"로 읽혀야 한다
        assertThat(changes.color()).isEqualTo(TextChange.clear());
        // 확인: 안 보낸 자산번호는 "그대로 둠"이라는 뜻으로 null이어야 한다
        assertThat(changes.assetNo()).isNull();
        // 확인: 상태는 수리 중으로 읽혀야 한다
        assertThat(changes.status()).isEqualTo(DeviceStatus.MAINTENANCE);
        // 확인: OS는 macOS로 읽혀야 한다
        assertThat(changes.os()).isEqualTo(DeviceOs.MACOS);
    }

    // [상황] 기종 치수를 소수 둘째 자리까지 넣는다.
    // [기대] 소수 한 자리로 반올림해 저장하고, 다시 저장하면 새 값으로 바뀐다.
    @Test
    @DisplayName("기종 치수는 소수 한 자리로 반올림해 저장하고, 다시 저장하면 값이 바뀐다")
    void modelDimensionsRounded() {
        // 준비: 견본 기종(맥북 에어 13 M2)을 만든다
        DeviceModel model = Fixtures.model();
        // 준비: 견본 기종 속성을 가져온다 (이름 등은 그대로 쓰려고)
        DeviceModel.Attributes a = Fixtures.mba13();
        // 실행: 사양은 비우고(null), 치수는 소수 둘째 자리까지(304.15·215·7.04·4.3) 넣어 관리자 2가 기종을 고친다
        model.apply(new DeviceModel.Attributes(a.name(), a.manufacturer(), a.formFactor(), null,
                new Dimensions(new BigDecimal("304.15"), new BigDecimal("215"), new BigDecimal("7.04"),
                        new BigDecimal("4.3"))), "admin0000000000000002");
        // (도구) isEqualByComparingTo = 숫자 크기만 비교 (304.2와 304.20을 같게 봄)
        // 확인: 가로 304.15는 소수 한 자리로 반올림해 304.2여야 한다
        assertThat(model.getDimensions().widthMm()).isEqualByComparingTo("304.2");
        // 확인: 세로 215는 215.0이어야 한다
        assertThat(model.getDimensions().depthMm()).isEqualByComparingTo("215.0");
        // 확인: 본체 두께 7.04는 반올림해 7.0이어야 한다
        assertThat(model.getDimensions().baseHMm()).isEqualByComparingTo("7.0");
        // 확인: 비우라고 보낸 사양은 null이어야 한다
        assertThat(model.getSpec()).isNull();
        // 확인: 바꾼 사람이 관리자 2로 남아야 한다
        assertThat(model.getUpdatedBy()).isEqualTo("admin0000000000000002");
    }

    // 도우미: 운영 코드의 수정 본문 해석기(DeviceUpdateParser.parse, 바깥에서 못 부르는 메서드)를 꺼내 JSON을 읽게 한다
    private DeviceChanges parse(String json) throws Exception {
        // 이름으로 해석기 클래스를 찾고 parse(JsonNode) 메서드 정보를 꺼낸다 (리플렉션 = 코드로 클래스·메서드를 찾아 다루는 기능)
        var method = Class.forName("com.gakdo.trace.rental.device.api.DeviceUpdateParser")
                .getDeclaredMethod("parse", com.fasterxml.jackson.databind.JsonNode.class);
        // 바깥에서 부를 수 없게 막힌 메서드를 테스트에서 부를 수 있게 연다
        method.setAccessible(true);
        // 아래에서 해석기를 부르다 예외가 나면 잡는다
        try {
            // JSON 글자를 트리로 읽어 해석기에 넘기고 결과를 DeviceChanges로 돌려준다
            return (DeviceChanges) method.invoke(null, mapper.readTree(json));
        // 리플렉션으로 부르면 원래 예외가 한 겹 감싸져 나오므로
        } catch (java.lang.reflect.InvocationTargetException e) {
            // 감싼 껍질을 벗겨 원래 예외(DomainException 등)를 그대로 던진다 (테스트가 원래 예외를 보게)
            throw (Exception) e.getCause();
        }
    }
}
