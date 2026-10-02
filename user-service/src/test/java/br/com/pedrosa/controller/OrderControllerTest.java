package br.com.pedrosa.controller;

import br.com.pedrosa.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "resilience4j.ratelimiter.instances.userService.limitForPeriod=3",
        "resilience4j.ratelimiter.instances.userService.limitRefreshPeriod=1h",
        "resilience4j.ratelimiter.instances.userService.timeoutDuration=0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OrderControllerTest {

    private static final String PATH = "/orders/displayOrders";
    private static final String CATEGORY = "clothes";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    private void performRequest(int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(
                get(PATH).param("category", CATEGORY)
        ).andReturn();

        if (result.getRequest().isAsyncStarted()) {
            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().is(expectedStatus));
        } else {
            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .status().is(expectedStatus).match(result);
        }
    }

    @Test
    void deveRetornar429QuandoExcederLimite() throws Exception {
        when(orderService.displayOrders(CATEGORY))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        // As 3 primeiras requisições devem passar
        for (int i = 0; i < 3; i++) {
            performRequest(200);
        }

        // A 4ª requisição deve ser bloqueada
        mockMvc.perform(get(PATH).param("category", CATEGORY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void deveRetornar200QuandoNaoExcederLimite() throws Exception {
        when(orderService.displayOrders(CATEGORY))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        for (int i = 0; i < 2; i++) {
            performRequest(200);
        }
    }
}
