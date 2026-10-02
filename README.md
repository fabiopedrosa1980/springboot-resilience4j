# Projeto de Estudo de Tolerancia a Falhas

Projeto de demonstração de padrões de resiliência com [Resilience4j](https://resilience4j.readme.io/) em uma arquitetura de dois microsserviços com **Spring Boot**:

- **Circuit Breaker**: interrompe as chamadas quando o catálogo falha e responde com *fallback*
- **Rate Limiter**: limita a quantidade de requisições aceitas pela API (HTTP 429)
- **Time Limiter**: define um tempo máximo para a chamada ao catálogo
- **Bulkhead** (thread pool): isola e limita as chamadas concorrentes ao catálogo

O `user-service` consome o `catalog-service` via HTTP. Quando o catálogo fica indisponível, lento ou começa a falhar, o `user-service` protege a si mesmo e responde com uma lista de produtos de *fallback*, evitando falhas em cascata.

## Arquitetura

```
┌──────────────┐   GET /orders/{category}   ┌─────────────────┐
│ user-service │ ─────────────────────────► │ catalog-service │
│  porta 9292  │  (Circuit Breaker, Rate    │   porta 9191    │
│              │   Limiter, Time Limiter    │                 │
│              │   e Bulkhead)              │                 │
└──────────────┘                            └─────────────────┘
        │                                            │
        └─ fallback com produtos estáticos           └─ H2 em memória
```

| Serviço | Porta | Descrição |
|---|---|---|
| `catalog-service` | 9191 | API de produtos com Spring Data JPA e banco H2 em memória, populado na inicialização pelo script `data.sql`. |
| `user-service` | 9292 | API que consome o catálogo protegida por Circuit Breaker, Rate Limiter, Time Limiter e Bulkhead (Resilience4j). |

## Tecnologias

- Java 27
- Spring Boot 4.1.1
- Resilience4j 2.4.0 (`resilience4j-spring-boot4`)
- Spring `RestClient`
- Spring Boot Starter AspectJ (necessário para as anotações do Resilience4j)
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
| GET | `/orders/displayOrders?category={category}` | Consulta o catálogo por categoria, protegida por Rate Limiter, Circuit Breaker, Time Limiter e Bulkhead |
| GET | `/actuator/health` | Saúde da aplicação, incluindo o estado do circuit breaker |
| GET | `/actuator/metrics` | Métricas da aplicação (inclui as métricas `resilience4j.*`) |

Respostas possíveis de `/orders/displayOrders`:

| Status | Quando acontece |
|---|---|
| `200 OK` | Dados reais do catálogo **ou** lista de *fallback* (circuito aberto, timeout, bulkhead cheio, catálogo fora do ar) |
| `429 Too Many Requests` | Limite do Rate Limiter excedido. Retorna o header `Retry-After: 60` |
| `503 Service Unavailable` | Circuito aberto ou falha de comunicação com o catálogo que não foi tratada pelo fallback (`Retry-After: 30` no caso do circuito aberto) |
| `504 Gateway Timeout` | Estouro do Time Limiter que não foi tratado pelo fallback |

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

## Ordem de execução dos padrões

O Rate Limiter é aplicado no controller, antes de qualquer chamada ao serviço. Dentro do `OrderService`, o Resilience4j aplica os aspectos em uma ordem fixa, **independente da ordem das anotações** no código:

```
Requisição
   │
   ▼
@RateLimiter (OrderController)        → excedeu o limite? 429
   │
   ▼
@CircuitBreaker (OrderService)        → circuito aberto? fallback
   │
   ▼
@TimeLimiter                          → passou de 5s? fallback
   │
   ▼
@Bulkhead (THREADPOOL)                → pool cheio? fallback
   │
   ▼
RestClient → catalog-service
```

O Circuit Breaker é o mais externo, então ele contabiliza como falha os erros do catálogo, os timeouts do Time Limiter e as rejeições do Bulkhead.

## Configuração do Rate Limiter

Definida em `application.yml` na instância `userService` e aplicada com `@RateLimiter(name = "userService")` no `OrderController`:

| Propriedade | Valor | Significado |
|---|---|---|
| `limitForPeriod` | `100` | Máximo de 100 requisições por período |
| `limitRefreshPeriod` | `1s` | O contador é renovado a cada 1 segundo |
| `timeoutDuration` | `0s` | Não espera por uma permissão. Se o limite foi atingido, rejeita imediatamente |
| `registerHealthIndicator` | `true` | Registra o health indicator do rate limiter |
| `eventConsumerBufferSize` | `100` | Tamanho do buffer de eventos |

Ao exceder o limite, o Resilience4j lança `RequestNotPermitted`, tratada pelo `GlobalExceptionHandler`, que responde **429** com o header `Retry-After: 60`.

## Configuração do Time Limiter

Aplicado com `@TimeLimiter(name = "userService")` no `OrderService`. Funciona com métodos que retornam `CompletableFuture`:

| Propriedade | Valor | Significado |
|---|---|---|
| `timeoutDuration` | `5s` | Tempo máximo de espera pela resposta do catálogo |
| `cancelRunningFuture` | `true` | Cancela a execução em andamento quando o tempo estoura |

Quando o tempo estoura, é lançada uma `TimeoutException`. Como o `@CircuitBreaker` possui fallback, na prática o fallback responde primeiro. O `GlobalExceptionHandler` mapeia a exceção para **504** como rede de segurança.

## Configuração do Bulkhead

O `OrderService` usa o Bulkhead do tipo **`THREADPOOL`**: as chamadas ao catálogo são executadas em um pool de threads próprio, isolado das threads do servidor web. Por isso o método retorna `CompletableFuture`.

```java
@Bulkhead(name = USER_SERVICE, type = Bulkhead.Type.THREADPOOL, fallbackMethod = "getAllAvailableProducts")
```

Configuração em `resilience4j.thread-pool-bulkhead.instances.userService`:

| Propriedade | Valor | Significado |
|---|---|---|
| `max-thread-pool-size` | `3` | Máximo de 3 chamadas simultâneas ao catálogo |
| `core-thread-pool-size` | `1` | Threads mantidas no pool |
| `queue-capacity` | `0` | Sem fila. Se as 3 threads estiverem ocupadas, a chamada é rejeitada |

Quando o pool está cheio, o Resilience4j lança `BulkheadFullException` e o método de fallback é executado.

> **Nota:** o `application.yml` também define `resilience4j.bulkhead.instances.userService` (`max-concurrent-calls: 3` e `max-wait-duration: 0s`), que é a configuração do Bulkhead do tipo `SEMAPHORE`. Como o código usa `THREADPOOL`, essa configuração **não é utilizada** no momento.

## Tratamento de erros

O `GlobalExceptionHandler` (`@RestControllerAdvice`) traduz as exceções do Resilience4j em respostas HTTP:

| Exceção | Status | Observação |
|---|---|---|
| `RequestNotPermitted` | 429 | Header `Retry-After: 60` |
| `CallNotPermittedException` | 503 | Header `Retry-After: 30` e corpo `ApiError` |
| `TimeoutException` | 504 | Corpo `ApiError` |
| `ResourceAccessException` | 503 | Falha de conexão do `RestClient` |

O corpo de erro tem o formato `{ "code": "...", "message": "..." }`.

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

## Testando Rate Limiter, Time Limiter e Bulkhead

### Rate Limiter

Para ver o `429` sem precisar de 100 requisições por segundo, reduza o limite ao subir o `user-service`:

```bash
cd user-service
./mvnw spring-boot:run -Dspring-boot.run.arguments="--resilience4j.ratelimiter.instances.userService.limitForPeriod=3"
```

Depois faça várias chamadas seguidas:

```bash
for i in $(seq 1 6); do
  curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:9292/orders/displayOrders?category=electronics"
done
```

As primeiras chamadas retornam `200` e as seguintes `429` (com o header `Retry-After`) até o período de 1 segundo ser renovado.

### Time Limiter e Bulkhead

O `catalog-service` responde rápido, então é preciso simular lentidão para exercitar esses dois padrões, por exemplo adicionando um `Thread.sleep(6000)` temporário em `catalog-service/.../service/OrderService.java`.

- **Time Limiter:** com o catálogo demorando mais de 5 segundos, a chamada é interrompida e o `user-service` responde com a lista de *fallback*.
- **Bulkhead:** dispare chamadas em paralelo. As 3 primeiras ocupam o pool, e as demais são rejeitadas imediatamente e respondidas com o *fallback*:

  ```bash
  seq 1 6 | xargs -P 6 -I{} curl -s -o /dev/null -w "%{http_code} %{time_total}s\n" \
    "http://localhost:9292/orders/displayOrders?category=electronics"
  ```

  No log do `user-service`, as mensagens `🔥 FALLBACK` mostram a thread e a exceção que acionaram o fallback (`BulkheadFullException`, `TimeoutException`, etc.).

Para acompanhar os padrões em tempo real, consulte `/actuator/health` e `/actuator/metrics` (por exemplo `resilience4j.circuitbreaker.state`, `resilience4j.ratelimiter.available.permissions` e `resilience4j.bulkhead.available.concurrent.calls`).

## Testes automatizados

```bash
cd user-service
./mvnw test
```

| Classe | O que valida |
|---|---|
| `OrderServiceTest` | Chamada ao catálogo por categoria, URL base com categoria nula e propagação de falhas do `RestClient` (usando `MockRestServiceServer`) |
| `OrderControllerTest` | Rate Limiter: configurado com limite de 3 requisições, valida que as 3 primeiras retornam `200` e a 4ª retorna `429` com o header `Retry-After` |

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
        ├── controller/OrderController.java  # @RateLimiter
        ├── dto/OrderDTO.java
        ├── exception/GlobalExceptionHandler.java  # 429, 503 e 504
        └── service/OrderService.java        # @Bulkhead + @CircuitBreaker + @TimeLimiter + fallback
    └── src/main/resources/application.yml   # Porta, actuator, Circuit Breaker, Rate Limiter, Time Limiter e Bulkhead
    └── src/test/java/br/com/pedrosa/        # OrderServiceTest e OrderControllerTest
```

## Configuração opcional

A URL do catálogo pode ser alterada pela propriedade `order.service.url` (padrão `http://localhost:9191/orders`):

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--order.service.url=http://host:porta/orders
```
