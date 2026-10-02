package br.com.pedrosa.service;


import br.com.pedrosa.dto.OrderDTO;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class OrderService {

    private final RestClient restClient;

    public static final String USER_SERVICE = "userService";

    public OrderService(RestClient restClient) {
        this.restClient = restClient;
    }

    @Bulkhead(name = USER_SERVICE,
            type = Bulkhead.Type.THREADPOOL,
            fallbackMethod = "getAllAvailableProducts")
    @CircuitBreaker(name = USER_SERVICE, fallbackMethod = "getAllAvailableProducts")
    @TimeLimiter(name = USER_SERVICE)
    public CompletableFuture<List<OrderDTO>> displayOrders(@RequestParam("category") String category) {
        List<OrderDTO> orders = restClient.get()
                .uri(category == null ? "" : "/{category}", category)
                .retrieve()
                .body(new ParameterizedTypeReference<List<OrderDTO>>() {
                });

        return CompletableFuture.completedFuture(orders);
    }

    private CompletableFuture<List<OrderDTO>> getAllAvailableProducts(String category, Exception e) {
        System.out.println(
                "🔥 FALLBACK: " +
                        Thread.currentThread().getName() +
                        " - " +
                        e.getClass().getName()
        );
        return CompletableFuture.completedFuture(List.of(
                new OrderDTO(119, "LED TV", "electronics", "white", 45000),
                new OrderDTO(345, "Headset", "electronics", "black", 7000),
                new OrderDTO(475, "Sound bar", "electronics", "black", 13000),
                new OrderDTO(574, "Puma Shoes", "foot wear", "black & white", 4600),
                new OrderDTO(678, "Vegetable chopper", "kitchen", "blue", 999),
                new OrderDTO(532, "Oven Gloves", "kitchen", "gray", 745)
        ));
    }

}
