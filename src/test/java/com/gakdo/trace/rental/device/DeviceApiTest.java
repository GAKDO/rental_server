package com.gakdo.trace.rental.device;

import static com.gakdo.trace.rental.support.TestJwts.admin;
import static com.gakdo.trace.rental.support.TestJwts.customer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.device.api.DeviceController;
import com.gakdo.trace.rental.device.application.DeviceService;
import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.domain.DeviceOs;
import com.gakdo.trace.rental.device.domain.DeviceStatus;
import com.gakdo.trace.rental.devicemodel.api.DeviceModelController;
import com.gakdo.trace.rental.devicemodel.application.DeviceModelService;
import com.gakdo.trace.rental.support.Fixtures;
import com.gakdo.trace.rental.support.TestJwts;
import com.gakdo.trace.rental.support.WebTestBase;

@WebMvcTest(controllers = {DeviceController.class, DeviceModelController.class})
class DeviceApiTest extends WebTestBase {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DeviceService devices;

    @MockitoBean
    private DeviceModelService models;

    @Test
    @DisplayName("기기 등록은 201과 계약 Device 형식(deviceId는 publicId)을 돌려준다")
    void registerDevice() throws Exception {
        Device device = Fixtures.device("C02EXAMPLE01");
        when(devices.register(eq("C02EXAMPLE01"), eq("MBA13-M2"), eq(DeviceOs.MACOS), eq("미드나이트"), eq("A-0001"),
                eq(TestJwts.ADMIN_ID))).thenReturn(device);
        mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                {"serial":"C02EXAMPLE01","modelCode":"MBA13-M2","os":"MACOS","color":"미드나이트","assetNo":"A-0001"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value(device.getPublicId()))
                .andExpect(jsonPath("$.modelName").value("MacBook Air (M2, 2022)"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.retiredAt").isEmpty())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    @DisplayName("같은 시리얼로 기기를 등록하면 409 DEVICE_SERIAL_EXISTS(Problem Details)")
    void duplicateSerial() throws Exception {
        when(devices.register(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DomainException(ErrorCode.DEVICE_SERIAL_EXISTS, "중복"));
        mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"serial\":\"C02EXAMPLE01\",\"modelCode\":\"MBA13-M2\",\"os\":\"MACOS\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DEVICE_SERIAL_EXISTS"))
                .andExpect(jsonPath("$.instance").value("/api/v1/devices"));
    }

    @Test
    @DisplayName("등록 본문의 모르는 필드·빠진 필드·틀린 코드 값은 400 VALIDATION_FAILED")
    void invalidCreateBody() throws Exception {
        mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"serial\":\"S\",\"modelCode\":\"MBA13-M2\",\"os\":\"MACOS\",\"status\":\"RETIRED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));
        mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"modelCode\":\"mba\",\"os\":\"MACOS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"serial\":\"S\",\"modelCode\":\"MBA13-M2\",\"os\":\"LINUX\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("os"));
        verifyNoInteractions(devices);
    }

    @Test
    @DisplayName("PATCH에 serial을 보내면 400이고 서비스는 불리지 않는다(시리얼은 등록 뒤 바꿀 수 없다)")
    void patchSerialRejected() throws Exception {
        mvc.perform(patch("/api/v1/devices/IPv6mcQOlTkKj6mOecAQt").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"serial\":\"NEW\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("serial"));
        verifyNoInteractions(devices);
    }

    @Test
    @DisplayName("진행 중 렌탈이 있는 기기를 수리 중으로 바꾸면 409 DEVICE_HAS_OPEN_RENTAL")
    void patchWithOpenRental() throws Exception {
        when(devices.change(eq("IPv6mcQOlTkKj6mOecAQt"), any(), eq(TestJwts.ADMIN_ID)))
                .thenThrow(new DomainException(ErrorCode.DEVICE_HAS_OPEN_RENTAL, "렌탈 중"));
        mvc.perform(patch("/api/v1/devices/IPv6mcQOlTkKj6mOecAQt").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"MAINTENANCE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_HAS_OPEN_RENTAL"));
    }

    @Test
    @DisplayName("폐기 기기를 사용 가능으로 바로 바꾸면 409 DEVICE_STATUS_TRANSITION_INVALID(Problem Details)")
    void patchRetiredToActive() throws Exception {
        when(devices.change(eq("IPv6mcQOlTkKj6mOecAQt"), any(), eq(TestJwts.ADMIN_ID)))
                .thenThrow(new DomainException(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID, "수리 중을 거쳐야 함"));
        mvc.perform(patch("/api/v1/devices/IPv6mcQOlTkKj6mOecAQt").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DEVICE_STATUS_TRANSITION_INVALID"))
                .andExpect(jsonPath("$.title").value("폐기한 기기는 수리 중으로만 되돌릴 수 있습니다"));
    }

    @Test
    @DisplayName("기기 목록은 기종·상태로 찾고 nextCursor를 돌려준다")
    void searchDevices() throws Exception {
        Device device = Fixtures.device("C02EXAMPLE01");
        when(devices.search(eq("MBA13-M2"), eq(DeviceStatus.ACTIVE), isNull(), isNull(), eq(1)))
                .thenReturn(new DeviceService.Page(List.of(device), "next"));
        mvc.perform(get("/api/v1/devices?modelCode=MBA13-M2&status=ACTIVE&limit=1").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].serial").value("C02EXAMPLE01"))
                .andExpect(jsonPath("$.nextCursor").value("next"));
    }

    @Test
    @DisplayName("목록 파라미터 형식 오류(limit 0, 상태 값, 깨진 커서)는 400")
    void searchInvalidParams() throws Exception {
        mvc.perform(get("/api/v1/devices?limit=0").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/devices?limit=101").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/devices?status=LOST").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
        mvc.perform(get("/api/v1/devices?cursor=@@@").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cursor"));
    }

    @Test
    @DisplayName("기기 API는 ADMIN만: 토큰 없으면 401 UNAUTHENTICATED, CUSTOMER면 403 FORBIDDEN")
    void deviceAccess() throws Exception {
        mvc.perform(get("/api/v1/devices"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/devices").with(customer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/devices").with(TestJwts.booth())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("모델명으로 3D 트윈 치수를 조회한다(CUSTOMER도 가능)")
    void getModelDimensions() throws Exception {
        when(models.get("MBA13-M2")).thenReturn(Fixtures.model());
        mvc.perform(get("/api/v1/device-models/MBA13-M2").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensions.widthMm").value(304.1))
                .andExpect(jsonPath("$.dimensions.lidHMm").value(4.3))
                .andExpect(jsonPath("$.spec.ramGb").value(8))
                .andExpect(jsonPath("$.formFactor").value("CLAMSHELL"));
    }

    @Test
    @DisplayName("기종 목록은 ADMIN만, 저장은 PUT 업서트이고 변경자를 넘긴다")
    void modelListAndUpsert() throws Exception {
        when(models.list()).thenReturn(List.of(Fixtures.model()));
        mvc.perform(get("/api/v1/device-models").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].modelCode").value("MBA13-M2"))
                .andExpect(jsonPath("$.items[0].dimensions").doesNotExist());
        mvc.perform(get("/api/v1/device-models").with(customer())).andExpect(status().isForbidden());

        when(models.upsert(eq("MBA13-M2"), any(), eq(TestJwts.ADMIN_ID))).thenReturn(Fixtures.model());
        mvc.perform(put("/api/v1/device-models/MBA13-M2").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"MacBook Air (M2, 2022)","manufacturer":"Apple","formFactor":"CLAMSHELL",
                         "spec":{"cpu":"Apple M2","ramGb":8,"ssdGb":256},
                         "dimensions":{"widthMm":304.1,"depthMm":215.0,"baseHMm":7.0,"lidHMm":4.3}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelCode").value("MBA13-M2"));
    }

    @Test
    @DisplayName("기종 저장: 치수 0 이하·빠진 치수·잘못된 기종 코드는 400, 없는 기종 조회는 404")
    void modelValidation() throws Exception {
        mvc.perform(put("/api/v1/device-models/MBA13-M2").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"formFactor\":\"CLAMSHELL\",\"dimensions\":{\"widthMm\":0,\"depthMm\":1,\"baseHMm\":1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        mvc.perform(get("/api/v1/device-models/mba13").with(admin())).andExpect(status().isBadRequest());
        when(models.get("NONE-1")).thenThrow(DomainException.notFound("기종"));
        mvc.perform(get("/api/v1/device-models/NONE-1").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
