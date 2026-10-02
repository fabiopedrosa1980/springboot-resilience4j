package br.com.pedrosa.service;

import br.com.pedrosa.dto.OrderDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OrderServiceTest {

    private static final String BASE_URL = "http://localhost:8080/orders";

    private MockRestServiceServer server;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        orderService = new OrderService(builder.build());
    }

    @Test
    @DisplayName("Deve buscar pedidos por categoria e retornar a lista")
    void shouldReturnOrdersByCategory() throws Exception {
        String json = """
                [
                  {"id":119,"name":"LED TV","category":"electronics","color":"white","price":45000},
                  {"id":345,"name":"Headset","category":"electronics","color":"black","price":7000}
                ]
                """;

        server.expect(requestTo(BASE_URL + "/electronics"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        CompletableFuture<List<OrderDTO>> future = orderService.displayOrders("electronics");
        List<OrderDTO> result = future.get();

        assertTrue(future.isDone());
        assertEquals(2, result.size());
        server.verify();
    }

    @Test
    @DisplayName("Deve chamar a URL base quando a categoria for nula")
    void shouldCallBaseUrlWhenCategoryIsNull() throws Exception {
        server.expect(requestTo(BASE_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<OrderDTO> result = orderService.displayOrders(null).get();

        assertNotNull(result);
        assertTrue(result.isEmpty());
        server.verify();
    }

    @Test
    @DisplayName("Deve propagar a exceção quando o serviço remoto falhar (o fallback é do Resilience4j)")
    void shouldPropagateExceptionWhenRemoteFails() {
        server.expect(requestTo(BASE_URL + "/electronics"))
                .andRespond(withServerError());

        // Sem o aspecto do Resilience4j, a exceção sobe direto do RestClient
        assertThrows(HttpServerErrorException.class,
                () -> orderService.displayOrders("electronics"));
        server.verify();
    }

    @Test
    @DisplayName("Fallback deve retornar a lista fixa de 6 produtos")
    void fallbackShouldReturnDefaultProducts() throws ExecutionException, InterruptedException {
        // O fallback é private, então é invocado via reflection
        @SuppressWarnings("unchecked")
        CompletableFuture<List<OrderDTO>> future = ReflectionTestUtils.invokeMethod(
                orderService,
                "getAllAvailableProducts",
                "electronics",
                new RuntimeException("boom"));

        assertNotNull(future);
        List<OrderDTO> result = future.get();

        assertEquals(6, result.size());
        assertTrue(result.contains(new OrderDTO(119, "LED TV", "electronics", "white", 45000)));
        assertTrue(result.contains(new OrderDTO(532, "Oven Gloves", "kitchen", "gray", 745)));
    }

    @Test
    @DisplayName("Fallback deve funcionar mesmo com categoria nula")
    void fallbackShouldWorkWithNullCategory() throws Exception {
        @SuppressWarnings("unchecked")
        CompletableFuture<List<OrderDTO>> future = ReflectionTestUtils.invokeMethod(
                orderService,
                "getAllAvailableProducts",
                null,
                new IllegalStateException("bulkhead full"));

        assertNotNull(future);
        assertEquals(6, future.get().size());
    }
}