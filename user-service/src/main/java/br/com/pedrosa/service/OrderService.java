package br.com.pedrosa.service;


import br.com.pedrosa.dto.OrderDTO;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class OrderService {

    private final RestClient restClient;

    public static final String USER_SERVICE="userService";

    private static final String BASEURL = "http://localhost:9191/orders";

    public OrderService(RestClient restClient) {
        this.restClient = restClient;
    }

    @CircuitBreaker(name = USER_SERVICE, fallbackMethod = "getAllAvailableProducts")
    public List<OrderDTO> displayOrders(@RequestParam("category") String category) {
        return restClient.get()
                .uri(category == null ? "" : "/{category}", category)
                .retrieve()
                .body(new ParameterizedTypeReference<List<OrderDTO>>() {});
    }

    public List<OrderDTO> getAllAvailableProducts(Exception e){
        return Stream.of(
                new OrderDTO(119, "LED TV", "electronics", "white", 45000),
                new OrderDTO(345, "Headset", "electronics", "black", 7000),
                new OrderDTO(475, "Sound bar", "electronics", "black", 13000),
                new OrderDTO(574, "Puma Shoes", "foot wear", "black & white", 4600),
                new OrderDTO(678, "Vegetable chopper", "kitchen", "blue", 999),
                new OrderDTO(532, "Oven Gloves", "kitchen", "gray", 745)
        ).collect(Collectors.toList());
    }

}
