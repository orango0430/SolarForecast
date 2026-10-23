package kr.solarcast.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 화면은 Thymeleaf 껍데기 + Chart.js. 데이터는 /api 조회로 그린다. */
@Controller
public class PageController {

	@GetMapping("/")
	public String dashboard() {
		return "dashboard";
	}

	@GetMapping("/models")
	public String models() {
		return "models";
	}

	@GetMapping("/admin")
	public String admin() {
		return "admin";
	}
}
