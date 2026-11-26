// 테스트 도구(테스트 자체는 아님): DB 없이 쓰는 기종(맥북 에어 13 M2, 치수 포함)과 기기 견본. 값은 계약 예시와 같다.

package com.gakdo.trace.rental.support;

import java.math.BigDecimal;

import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.domain.DeviceOs;
import com.gakdo.trace.rental.devicemodel.domain.DeviceModel;
import com.gakdo.trace.rental.devicemodel.domain.Dimensions;
import com.gakdo.trace.rental.devicemodel.domain.FormFactor;
import com.gakdo.trace.rental.devicemodel.domain.ModelSpec;

/** DB 없이 쓰는 도메인 객체. 값은 contracts 예시와 같다. */
public final class Fixtures {

    private Fixtures() {
    }

    // 맥북 에어 13(M2) 기종의 속성(이름·제조사·형태·사양·치수) 견본을 만든다
    public static DeviceModel.Attributes mba13() {
        // 계약 예시와 같은 값으로 채운다: 이름, 제조사 Apple, 접는 노트북(CLAMSHELL), CPU·RAM·저장공간, 가로·세로·본체 두께·덮개 두께(mm)
        return new DeviceModel.Attributes("MacBook Air (M2, 2022)", "Apple", FormFactor.CLAMSHELL,
                new ModelSpec("Apple M2", 8, 256, null),
                new Dimensions(new BigDecimal("304.1"), new BigDecimal("215.0"), new BigDecimal("7.0"),
                        new BigDecimal("4.3")));
    }

    // 위 속성으로 기종 코드 MBA13-M2 기종 객체를 만든다 (만든 사람은 테스트 관리자)
    public static DeviceModel model() {
        // 기종 코드·속성·만든 관리자 ID를 넣어 기종을 만든다
        return DeviceModel.create("MBA13-M2", mba13(), TestJwts.ADMIN_ID);
    }

    // 받은 시리얼 번호로 사용 가능한 테스트 기기 한 대를 만든다 (렌탈 테스트마다 다른 시리얼로 부름)
    public static Device device(String serial) {
        // 시리얼, 위 기종, macOS, 색상 미드나이트, 자산번호 A-0001, 등록한 관리자 ID로 기기를 등록한다
        return Device.register(serial, model(), DeviceOs.MACOS, "미드나이트", "A-0001", TestJwts.ADMIN_ID);
    }
}
