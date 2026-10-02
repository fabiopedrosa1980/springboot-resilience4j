package br.com.pedrosa.service;

import br.com.pedrosa.entity.Order;
import br.com.pedrosa.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@Service
public class OrderService {
    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public List<Order> getOrders(){
        return orderRepository.findAll();
    }

    public List<Order> getOrdersByCategory(@PathVariable String category){
        return orderRepository.findByCategory(category);
    }
}
