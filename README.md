# springboot-resilience4j

Projeto de demonstração do padrão **Circuit Breaker** com [Resilience4j](https://resilience4j.readme.io/) em uma arquitetura de dois microsserviços com **Spring Boot**.

O `user-service` consome o `catalog-service` via HTTP. Quando o catálogo fica indisponível ou começa a falhar, o circuito é aberto e o `user-service` responde com uma lista de produtos de *fallback*, evitando falhas em cascata.

## Arquitetura

```
┌──────────────┐   GET /orders/{category}   ┌─────────────────┐
│ user-service │ ─────────────────────────► │ catalog-service │
│  porta 9292  │      (Circuit Breaker)     │   porta 9191    │
└──────────────┘                            └─────────────────┘
        │                                            │
        └─ fallback com produtos estáticos           └─ H2 em memória
```

| Serviço | Porta | Descrição |
|---|---|---|
| `catalog-service` | 9191 | API de produtos com Spring Data JPA e banco H2 em memória, populado na inicialização pelo script `data.sql`. |
| `user-service` | 9292 | API que consome o catálogo protegida por Circuit Breaker (Resilience4j). |

## Tecnologias

- Java 27
- Spring Boot 4.1.1
- Resilience4j 2.4.0 (`resilience4j-spring-boot4`)
- Spring `RestClient`
- Spring Data JPA + H2 (catalog-service)
- Spring Boot Actuator (catalog-service e user-service) + Micrometer Prometheus (user-service)
- Lombok
- Maven (wrapper incluso)

## Pré-requisitos

- JDK 27 (ou ajuste `java.version` nos `pom.xml`)
- Não é necessário instalar o Maven, pois cada módulo possui o `mvnw`

## Como executar

Suba primeiro o `catalog-service`, depois o `user-service`, cada um em um terminal.

```bash
# Terminal 1 - catalog-service (porta 9191)
cd catalog-service
./mvnw spring-boot:run

# Terminal 2 - user-service (porta 9292)
cd user-service
./mvnw spring-boot:run
```

No Windows, use `mvnw.cmd` no lugar de `./mvnw`.

## Endpoints

### catalog-service (`http://localhost:9191`)

| Método | Rota | Descrição |
|---|---|---|
| GET | `/orders` | Lista todos os produtos |
| GET | `/orders/{category}` | Lista produtos por categoria (`electronics`, `clothes`) |
| GET | `/h2-console` | Console do H2 (veja [Banco de dados](#banco-de-dados-catalog-service)) |

### user-service (`http://localhost:9292`)

| Método | Rota | Descrição |
|---|---|---|
| GET | `/orders/displayOrders?category={category}` | Consulta o catálogo por categoria, protegida por Circuit Breaker |
| GET | `/actuator/health` | Saúde da aplicação, incluindo o estado do circuit breaker |

Exemplo:

```bash
curl "http://localhost:9292/orders/displayOrders?category=electronics"
```

## Banco de dados (catalog-service)

O `catalog-service` usa um banco **H2 em memória**, recriado a cada inicialização:

- A tabela `ORDERS_TBL` é gerada pelo Hibernate a partir da entidade `Order` (`ddl-auto: create`, com o `id` do tipo `Long` e geração `IDENTITY`).
- Os dados iniciais são inseridos pelo script `src/main/resources/data.sql` (6 produtos nas categorias `electronics` e `clothes`). Ele é executado após a criação do schema (`defer-datasource-initialization: true` e `spring.sql.init.mode: always`).
- Os comandos SQL são exibidos no log (`show-sql: true`).

Para acessar o console do H2, abra `http://localhost:9191/h2-console` e use:

| Campo | Valor |
|---|---|
| JDBC URL | `jdbc:h2:mem:testdb` |
| User Name | `sa` |
| Password | *(vazio)* |

## Configuração do Circuit Breaker

Definida em `user-service/src/main/resources/application.yml` na instância `userService`:

| Propriedade | Valor | Significado |
|---|---|---|
| `slidingWindowType` | `COUNT_BASED` | Janela deslizante baseada em número de chamadas |
| `slidingWindowSize` | `10` | Últimas 10 chamadas são avaliadas |
| `minimumNumberOfCalls` | `5` | Mínimo de chamadas antes de calcular a taxa de falha |
| `failureRateThreshold` | `50` | Abre o circuito com 50% ou mais de falhas |
| `waitDurationInOpenState` | `5s` | Tempo com o circuito aberto antes de testar a recuperação |
| `automaticTransitionFromOpenToHalfOpenEnabled` | `true` | Passa automaticamente de aberto para semiaberto |
| `permittedNumberOfCallsInHalfOpenState` | `3` | Chamadas de teste permitidas no estado semiaberto |
| `registerHealthIndicator` | `true` | Expõe o estado no `/actuator/health` |

O método anotado com `@CircuitBreaker(name = "userService", fallbackMethod = "getAllAvailableProducts")` está em `OrderService`. O fallback retorna uma lista fixa de produtos (LED TV, Headset, Sound bar, etc.).

## Testando o Circuit Breaker

1. Com os dois serviços no ar, chame o endpoint e veja os dados reais do catálogo:
   ```bash
   curl "http://localhost:9292/orders/displayOrders?category=electronics"
   ```
2. Pare o `catalog-service` (`Ctrl+C`).
3. Faça várias chamadas seguidas (6 ou mais). Todas passam a retornar a lista de **fallback**.
4. Consulte o estado do circuito:
   ```bash
   curl http://localhost:9292/actuator/health
   ```
   O componente `circuitBreakers` deve mostrar `userService` como `OPEN`.
5. Suba o `catalog-service` novamente. Após 5 segundos o circuito vai para `HALF_OPEN` e, com as chamadas de teste bem-sucedidas, volta para `CLOSED`, retornando os dados reais.

## Estrutura do projeto

```
springboot-resilience4j/
├── catalog-service/
│   └── src/main/java/br/com/pedrosa/
│       ├── CatalogServiceApplication.java
│       ├── controller/OrderController.java
│       ├── entity/Order.java
│       ├── repository/OrderRepository.java
│       └── service/OrderService.java
│   └── src/main/resources/
│       ├── application.yml                  # Porta, H2 e JPA
│       └── data.sql                         # Carga inicial dos produtos
└── user-service/
    └── src/main/java/br/com/pedrosa/
        ├── UserServiceApplication.java
        ├── config/RestClientConfig.java     # RestClient apontando para o catálogo
        ├── controller/OrderController.java
        ├── dto/OrderDTO.java
        └── service/OrderService.java        # @CircuitBreaker + fallback
    └── src/main/resources/application.yml   # Porta, actuator e Circuit Breaker
```

## Configuração opcional

A URL do catálogo pode ser alterada pela propriedade `order.service.url` (padrão `http://localhost:9191/orders`):

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--order.service.url=http://host:porta/orders
```

