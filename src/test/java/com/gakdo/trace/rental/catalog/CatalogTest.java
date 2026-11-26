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

// (도구) @WebMvcTest = 서버 전체 대신 지정한 컨트롤러(CatalogController)만 띄우는 가벼운 API 테스트. WebTestBase로 운영 보안 규칙도 함께 띄운다
@WebMvcTest(controllers = CatalogController.class)
class CatalogTest extends WebTestBase {

    // (도구) MockMvc = 진짜 네트워크 없이 서버에 HTTP 요청을 보낸 것처럼 흉내 내는 도구. @Autowired = 스프링이 만들어 넣어 준다
    @Autowired
    private MockMvc mvc;

    // (도구) @MockitoBean = 진짜 서비스 대신 가짜(목) 서비스를 스프링에 끼워 넣는다
    @MockitoBean
    private CatalogService service;

    // 도우미: DB에서 읽은 기기 한 줄을 흉내 낸다(맥북 에어 M2).
    private static DeviceRow row(long id, String status, boolean open, String spec) {
        // 기기 한 줄을 만든다: 내부 번호, 공개 ID(끝에 id 붙임), OS, 색, 상태, 기종 코드·이름·제조사, 사양 JSON,
        // 가로·세로·본체 두께·덮개 두께, 렌탈 횟수 3, 진행 중 렌탈 있음 여부(open)
        return new DeviceRow(id, "aAXQFO2Vz6ZTFbPctC2d" + id, "MACOS", "미드나이트", status, "MBA13-M2",
                "MacBook Air (M2, 2022)", "Apple", spec, new BigDecimal("304.1"), new BigDecimal("215.0"),
                new BigDecimal("6.0"), new BigDecimal("5.3"), 3, open);
    }

    // [상황] 로그인 없이 공개 기기 목록을 본다.
    // [기대] 기종·대여 가능 여부·사양·치수·검사 시점은 보이지만 일련번호·자산번호는 없다(개인·자산 정보 보호).
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("공개 기기 목록은 로그인 없이 열리고 시리얼·자산번호 없이 기종·치수·시점을 보낸다")
    void publicListHasNoSerial() throws Exception {
        // 준비: 가짜 저장소(기기 1대: 사용 가능·진행 중 렌탈 없음·CPU M2 / 끝난 반납 시점 1개)로 진짜 목록 서비스를 만든다
        CatalogService real = new CatalogService(new FakeStore(List.of(row(1, "ACTIVE", false, "{\"cpu\":\"Apple M2\",\"ramGb\":16}")),
                List.of(new CheckpointRow(1, "Q2xxYv8oP0aLkM3nB7cDe", "RET", "SEALED", OffsetDateTime.parse("2026-11-12T18:00:00+09:00")))),
                new ObjectMapper());
        // (도구) when(가짜.메서드()).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것
        // 준비: 컨트롤러가 쓰는 가짜 서비스가 목록을 달라고 하면 위 진짜 서비스가 만든 목록을 돌려주게 한다
        when(service.list()).thenReturn(real.list());
        // (도구) perform(get(주소)) = 그 주소로 GET 요청을 보낸다. andExpect(...) = 응답이 이래야 한다는 검사를 붙인다
        // 실행: 로그인 토큰 없이 공개 기기 목록 주소로 GET 요청을 보낸다
        mvc.perform(get("/api/v1/catalog/devices"))
                // 확인: 응답 코드가 200(성공)이어야 한다 = 로그인 없이 열린다
                .andExpect(status().isOk())
                // (도구) jsonPath("$.items[0].칸") = 응답 JSON의 items 목록 첫 번째([0]) 항목에서 그 칸 값을 꺼낸다
                // 확인: 첫 기기의 기종 코드가 MBA13-M2여야 한다
                .andExpect(jsonPath("$.items[0].modelCode").value("MBA13-M2"))
                // 확인: 사용 가능 상태이고 진행 중 렌탈이 없으니 대여 가능(true)이어야 한다
                .andExpect(jsonPath("$.items[0].available").value(true))
                // 확인: 사양 JSON에서 읽은 CPU가 "Apple M2"여야 한다
                .andExpect(jsonPath("$.items[0].spec.cpu").value("Apple M2"))
                // 확인: 치수의 가로가 304.1mm여야 한다
                .andExpect(jsonPath("$.items[0].dimensions.widthMm").value(304.1))
                // 확인: 첫 기기의 첫 검사 시점 ID가 가짜 저장소에 넣은 값이어야 한다
                .andExpect(jsonPath("$.items[0].checkpoints[0].checkpointId").value("Q2xxYv8oP0aLkM3nB7cDe"))
                // 확인: 일련번호(serial) 칸은 아예 없어야 한다 (doesNotExist = 그 칸이 없어야 함, 개인·자산 정보 보호)
                .andExpect(jsonPath("$.items[0].serial").doesNotExist())
                // 확인: 자산번호(assetNo) 칸도 없어야 한다
                .andExpect(jsonPath("$.items[0].assetNo").doesNotExist());
    }

    // [상황] 진행 중 렌탈이 있는 기기, 수리 중 기기, 빈 기기가 있다. 첫 기기는 시점이 20개이고 사양 값이 깨져 있다.
    // [기대] 앞 둘은 대여 불가, 셋째만 가능. 시점은 기기마다 최근 12개까지, 깨진 사양은 비운다.
    @Test
    @DisplayName("진행 중 렌탈이 있거나 정비 중이면 대여 불가, 시점은 기기마다 최근 12개까지, 깨진 사양은 null")
    void availabilityAndCheckpointLimit() {
        // 준비: 시점 줄들을 담을 목록을 만든다
        List<CheckpointRow> checkpoints = new ArrayList<>();
        // 준비: 20번 되풀이한다 (최대 12개 자르기를 시험하려고 12보다 많이)
        for (int i = 0; i < 20; i++) {
            // 준비: 1번 기기의 봉인된 출고 시점 한 줄을 넣는다 (ID 끝에 00~19 두 자리 번호)
            checkpoints.add(new CheckpointRow(1, "Q2xxYv8oP0aLkM3nB7c" + String.format("%02d", i), "OUT", "SEALED", null));
        }
        // 준비: 기기 3대(1번 사용 가능+진행 중 렌탈+깨진 사양 "{broken", 2번 수리 중, 3번 사용 가능·빈 기기)와 위 시점 20개로 서비스를 만든다
        CatalogService svc = new CatalogService(new FakeStore(
                List.of(row(1, "ACTIVE", true, "{broken"), row(2, "MAINTENANCE", false, null), row(3, "ACTIVE", false, null)),
                checkpoints), new ObjectMapper());
        // 실행: 공개 기기 목록을 만든다
        List<CatalogDevice> list = svc.list();
        // (도구) assertThat(목록).extracting(뽑을 값).containsExactly(...) = 각 항목에서 값을 뽑아 정확히 이 순서·이 값들인지 검사
        // 확인: 대여 가능 여부가 1번 불가(렌탈 중), 2번 불가(수리 중), 3번 가능이어야 한다
        assertThat(list).extracting(CatalogDevice::available).containsExactly(false, false, true);
        // 확인: 1번 기기의 시점은 20개 중 최근 12개(MAX_CHECKPOINTS)까지만 있어야 한다 (hasSize = 개수 검사)
        assertThat(list.get(0).checkpoints()).hasSize(CatalogService.MAX_CHECKPOINTS);
        // 확인: 깨진 사양 JSON은 읽지 못하니 비워(null) 둬야 한다 (목록 전체가 실패하지 않게)
        assertThat(list.get(0).spec()).isNull();
        // 확인: 2번 기기는 시점이 없으니 빈 목록이어야 한다
        assertThat(list.get(1).checkpoints()).isEmpty();
    }

    // [상황] 등록된 기기가 하나도 없다.
    // [기대] 빈 목록을 주고, 시점을 조회하러 DB에 가지도 않는다.
    @Test
    @DisplayName("기기가 없으면 시점을 묻지 않고 빈 목록")
    void emptyCatalog() {
        // 실행+확인: 기기가 0대인 가짜 저장소로 목록을 만들면 빈 목록이어야 한다. 시점 목록을 null로 줘서 시점을 물으면 바로 실패하게 해 둠
        assertThat(new CatalogService(new FakeStore(List.of(), null), new ObjectMapper()).list()).isEmpty();
    }

    // 도우미: DB 대신 정해 둔 기기·시점을 돌려주는 가짜 저장소.
    private record FakeStore(List<DeviceRow> devices, List<CheckpointRow> checkpoints) implements CatalogStore {
        // (도구) @Override = 부모(CatalogStore)에 정해진 메서드를 여기서 직접 만든다는 표시
        @Override
        // 기기 목록을 달라고 하면
        public List<DeviceRow> devices(int limit) {
            // 정해 둔 기기 목록을 그대로 돌려준다
            return devices;
        }

        @Override
        // 끝난 시점 목록을 달라고 하면
        public List<CheckpointRow> finishedCheckpoints(List<Long> deviceIds) {
            // 시점 목록을 null로 만들어 둔 경우(= 불리면 안 되는 경우)라면
            if (checkpoints == null) {
                // 테스트를 실패시킨다 (기기가 없는데 시점을 물으러 DB에 가면 안 됨)
                throw new AssertionError("기기가 없으면 부르지 않는다");
            }
            // 정해 둔 시점 목록을 돌려준다
            return checkpoints;
        }
    }
}
