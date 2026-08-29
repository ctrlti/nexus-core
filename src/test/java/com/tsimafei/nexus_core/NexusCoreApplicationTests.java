package com.tsimafei.nexus_core;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

@SpringBootTest
class NexusCoreApplicationTests {

	@MockitoBean
	private TelegramBotsLongPollingApplication telegramBotsLongPollingApplication;

	@Test
	void contextLoads() {
		// test
	}
}