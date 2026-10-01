package br.com.pedrosa.dto;

public record OrderDTO(
        int id,
        String name,
        String category,
        String color,
        double price
) {
}
