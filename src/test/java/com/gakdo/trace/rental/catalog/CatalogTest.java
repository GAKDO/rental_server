// 이 파일이 확인하는 것: 공개 보유 기기 목록(로그인 없이 누구나 보는 "어떤 노트북이 있나" 화면).

package com.gakdo.trace.rental.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.rental.catalog.api.CatalogController;
import com.gakdo.trace.rental.catalog.application.CatalogService;
import com.gakdo.trace.rental.catalog.application.CatalogService.CatalogDevice;
import com.gakdo.trace.rental.catalog.application.CatalogStore;
import com.gakdo.trace.rental.catalog.application.CatalogStore.CheckpointRow;
import com.gakdo.trace.rental.catalog.application.CatalogStore.DeviceRow;
import com.gakdo.trace.rental.support.WebTestBase;

@WebMvcTest(controllers = CatalogController.class)
class CatalogTest extends WebTestBase {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CatalogService service;

    // 도우미: DB에서 읽은 기기 한 줄을 흉내 낸다(맥북 에어 M2).
    private static DeviceRow row(long id, String status, boolean open, String spec) {
        return new DeviceRow(id, "aAXQFO2Vz6ZTFbPctC2d" + id, "MACOS", "미드나이트", status, "MBA13-M2",
                "MacBook Air (M2, 2022)", "Apple", spec, new BigDecimal("304.1"), new BigDecimal("215.0"),
                new BigDecimal("6.0"), new BigDecimal("5.3"), 3, open);
    }

    // [상황] 로그인 없이 공개 기기 목록을 본다.
    // [기대] 기종·대여 가능 여부·사양·치수·검사 시점은 보이지만 일련번호·자산번호는 없다(개인·자산 정보 보호).
    @Test
    @DisplayName("공개 기기 목록은 로그인 없이 열리고 시리얼·자산번호 없이 기종·치수·시점을 보낸다")
    void publicListHasNoSerial() throws Exception {
        CatalogService real = new CatalogService(new FakeStore(List.of(row(1, "ACTIVE", false, "{\"cpu\":\"Apple M2\",\"ramGb\":16}")),
                List.of(new CheckpointRow(1, "Q2xxYv8oP0aLkM3nB7cDe", "RET", "SEALED", OffsetDateTime.parse("2026-11-12T18:00:00+09:00")))),
                new ObjectMapper());
        when(service.list()).thenReturn(real.list());
        mvc.perform(get("/api/v1/catalog/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].modelCode").value("MBA13-M2"))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.items[0].spec.cpu").value("Apple M2"))
                .andExpect(jsonPath("$.items[0].dimensions.widthMm").value(304.1))
                .andExpect(jsonPath("$.items[0].checkpoints[0].checkpointId").value("Q2xxYv8oP0aLkM3nB7cDe"))
                .andExpect(jsonPath("$.items[0].serial").doesNotExist())
                .andExpect(jsonPath("$.items[0].assetNo").doesNotExist());
    }

    // [상황] 진행 중 렌탈이 있는 기기, 수리 중 기기, 빈 기기가 있다. 첫 기기는 시점이 20개이고 사양 값이 깨져 있다.
    // [기대] 앞 둘은 대여 불가, 셋째만 가능. 시점은 기기마다 최근 12개까지, 깨진 사양은 비운다.
    @Test
    @DisplayName("진행 중 렌탈이 있거나 정비 중이면 대여 불가, 시점은 기기마다 최근 12개까지, 깨진 사양은 null")
    void availabilityAndCheckpointLimit() {
        List<CheckpointRow> checkpoints = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            checkpoints.add(new CheckpointRow(1, "Q2xxYv8oP0aLkM3nB7c" + String.format("%02d", i), "OUT", "SEALED", null));
        }
        CatalogService svc = new CatalogService(new FakeStore(
                List.of(row(1, "ACTIVE", true, "{broken"), row(2, "MAINTENANCE", false, null), row(3, "ACTIVE", false, null)),
                checkpoints), new ObjectMapper());
        List<CatalogDevice> list = svc.list();
        assertThat(list).extracting(CatalogDevice::available).containsExactly(false, false, true);
        assertThat(list.get(0).checkpoints()).hasSize(CatalogService.MAX_CHECKPOINTS);
        assertThat(list.get(0).spec()).isNull();
        assertThat(list.get(1).checkpoints()).isEmpty();
    }

    // [상황] 등록된 기기가 하나도 없다.
    // [기대] 빈 목록을 주고, 시점을 조회하러 DB에 가지도 않는다.
    @Test
    @DisplayName("기기가 없으면 시점을 묻지 않고 빈 목록")
    void emptyCatalog() {
        assertThat(new CatalogService(new FakeStore(List.of(), null), new ObjectMapper()).list()).isEmpty();
    }

    // 도우미: DB 대신 정해 둔 기기·시점을 돌려주는 가짜 저장소.
    private record FakeStore(List<DeviceRow> devices, List<CheckpointRow> checkpoints) implements CatalogStore {
        @Override
        public List<DeviceRow> devices(int limit) {
            return devices;
        }

        @Override
        public List<CheckpointRow> finishedCheckpoints(List<Long> deviceIds) {
            if (checkpoints == null) {
                throw new AssertionError("기기가 없으면 부르지 않는다");
            }
            return checkpoints;
        }
    }
}
