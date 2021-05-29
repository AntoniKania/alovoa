package com.nonononoki.alovoa.html;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class DonateListResourceTest {
	
	@Autowired
	private DonateListResource donateListResource;

	@Test
	public void test() throws Exception {
		donateListResource.donate();
	}
}