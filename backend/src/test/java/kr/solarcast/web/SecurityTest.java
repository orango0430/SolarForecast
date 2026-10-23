package kr.solarcast.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityTest {

	@Autowired
	MockMvc mvc;

	@Test
	void publicReadApis() throws Exception {
		mvc.perform(get("/api/forecasts?target=solar&date=2025-10-01")).andExpect(status().isOk());
		mvc.perform(get("/api/models")).andExpect(status().isOk());
		mvc.perform(get("/")).andExpect(status().isOk());
	}

	@Test
	void adminRequiresAuth() throws Exception {
		mvc.perform(post("/api/admin/replay/stop")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/admin/replay/stop").with(httpBasic("admin", "wrong"))).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/admin/replay/stop").with(httpBasic("admin", "admin"))).andExpect(status().isOk());
	}

	@Test
	void forecastFailsCleanlyWithoutData() throws Exception {
		// plant 데이터가 없으면 409로 응답 (500이 아니라)
		mvc.perform(post("/api/admin/forecasts/run?date=2025-10-01").with(httpBasic("admin", "admin")))
				.andExpect(status().isConflict());
	}
}
