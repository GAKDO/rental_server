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

    public static DeviceModel.Attributes mba13() {
        return new DeviceModel.Attributes("MacBook Air (M2, 2022)", "Apple", FormFactor.CLAMSHELL,
                new ModelSpec("Apple M2", 8, 256, null),
                new Dimensions(new BigDecimal("304.1"), new BigDecimal("215.0"), new BigDecimal("7.0"),
                        new BigDecimal("4.3")));
    }

    public static DeviceModel model() {
        return DeviceModel.create("MBA13-M2", mba13(), TestJwts.ADMIN_ID);
    }

    public static Device device(String serial) {
        return Device.register(serial, model(), DeviceOs.MACOS, "미드나이트", "A-0001", TestJwts.ADMIN_ID);
    }
}
