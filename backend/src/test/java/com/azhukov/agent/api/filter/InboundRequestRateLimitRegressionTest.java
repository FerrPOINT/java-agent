package com.azhukov.agent.api.filter;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InboundRequestRateLimitRegressionTest {
    @Configuration
    @ComponentScan(basePackages = "com.azhukov.agent.api.filter", useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.azhukov\\.agent\\.api\\.filter\\.RateLimitFilter"))
    static class ScannedFilters { }

    @RestController
    static class ProbeController {
        @GetMapping("/agent/session/probe/review/pending")
        String pending() { return "ok"; }
    }

    @Test
    void repeatedRequestsFromSameAddressAreNotRejectedByInboundRateLimiter() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(ScannedFilters.class)) {
            Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(Filter[]::new);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController()).addFilters(filters).build();
            for (int i = 0; i < 250; i++) {
                mvc.perform(get("/agent/session/probe/review/pending"))
                    .andExpect(status().isOk());
            }
        }
    }
}
