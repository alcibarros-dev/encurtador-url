# ADR-0001 — Arquitetura do Encurtador de URLs

| Campo | Valor |
|---|---|
| Status | **Aceito** (2026-10-31), após a revisão 1 · plano de implementação concluído (F0 a F6) · D2 e D9 substituídas pelos ADRs 0002 e 0003 (aceitos em 2026-11-02; o 0002 implementado, o 0003 pendente); as decisões ajustadas por eles têm uma nota no início |
| Data | 2026-10-31 (rev. 1 e aceite no mesmo dia) |
| Escopo | Reescrita completa do projeto `Antony-Reis/encurtadorUrl` |
| Stack | Java 25 · Spring Boot 4.1.2 · PostgreSQL 18 · Redis 8 · Kafka 4.4 |
| Decisores | Dono do projeto (aprovação) · autor da proposta · revisor de arquitetura independente (§8) |

**Direcionadores.** Projeto de estudo e portfólio; uma instância como alvo, mas desenho stateless; custo de
infraestrutura zero (tudo roda local em Docker); qualidade verificável por testes automatizados.

**Regra de evolução.** Este ADR, uma vez aceito, não é reescrito. Alterar uma decisão `Dn` gera um novo ADR que a
substitui; a decisão antiga recebe a marcação `Substituída por ADR-000X`.

---

## 1. Contexto

### 1.1 Ponto de partida

O projeto original (Spring Boot 4.1.1, PostgreSQL, Redis, Kafka, JWT) tem boa intenção arquitetural, mas a análise
estática encontrou falhas justamente na integração entre as peças:

| # | Problema no original | Efeito |
|---|---|---|
| P1 | `DELETE` não invalida o Redis | link apagado continua redirecionando por até 10 dias |
| P2 | `kafkaTemplate.send()` no fluxo do redirect, dentro de `@Transactional` | Kafka fora do ar trava o redirect e retém conexões do pool até 60 s, o que pode derrubar a API inteira |
| P3 | Status "Expirada" gravado e exceção lançada na mesma transação | rollback: o status nunca persiste |
| P4 | `@CreationTimestamp` em `acessadaQuando` | horário do clique é o do consumo, não o do acesso |
| P5 | Filtro JWT customizado sem tratamento de `JwtException` | token inválido/expirado devolve 500 |
| P6 | Swagger fora do `permitAll` | documentação inacessível (403) |
| P7 | Handler de `java.nio.file.AccessDeniedException` | handler morto (classe errada) |
| P8 | Expiração do JWT com fuso fixo `-03:00` | validade depende do fuso do servidor |
| P9 | `PATCH /v1/auth` público + cast do principal | 500 (`ClassCastException`) sem token |
| P10 | `create-drop` + Redis persistente | após restart, métricas caem em links de outros usuários |
| P11 | `deleteUrl` remove fisicamente link e cliques | o código volta a ficar disponível para reuso |
| P12 | Cota check-then-act (`countByUsers` antes do `save`) | burlável com requisições concorrentes |
| P13 | Redis gravado dentro da transação de criação, antes do commit | falha no commit deixa um link fantasma redirecionando por 10 dias |
| S1 | Nenhuma validação de entrada (sem Bean Validation) | URLs/senhas arbitrárias, vários 500 |
| S2 | Login diferencia "e-mail não existe" de "senha errada" | enumeração de usuários |
| S3 | Troca de senha sem senha atual; tokens não revogados | sequestro de conta facilitado |
| S4 | Códigos de status incoerentes (posse → 400, sem auth → 403) | contrato confuso |
| Q1 | Hash SHA-256 truncado + `exists` antes do `save` | colisões e corrida check-then-act |
| Q2 | Testes só de `UserService`/`AuthService`; `contextLoads` exige infra | núcleo sem cobertura |

A reescrita não é uma refatoração: é um projeto novo que preserva o objetivo (encurtador com contas, cache e métricas
assíncronas) e corrige os problemas por construção.

### 1.2 O que caracteriza um bom encurtador de URLs

1. **O redirecionamento é o caminho quente.** Leitura domina escrita (ordem de 100:1). Ele precisa ser rápido, barato
   e disponível mesmo quando componentes secundários (cache, analytics) falham.
2. **O código curto é um identificador público e permanente.** Deve ser único por construção, curto, difícil de
   enumerar e **nunca reutilizado** — um link já compartilhado jamais pode passar a apontar para outro destino.
3. **O ciclo de vida é explícito e tem efeito imediato.** Expirar ou remover um link precisa refletir no próximo
   acesso, inclusive com cache.
4. **Analytics não custa nada ao redirect.** Contagem de cliques é eventual e tolerante a perdas pontuais; o redirect
   não espera por ela.
5. **O destino é tratado como entrada hostil.** Só `http`/`https`, sem credenciais embutidas, sem loops para o próprio
   encurtador, com limites de tamanho.
6. **Abuso é esperado.** Rate limit em criação, login e resolução; cota por usuário; respostas que não vazam
   informação.
7. **É operável.** Health checks, métricas do caminho quente, logs estruturados sem dados sensíveis.

### 1.3 Requisitos funcionais

| ID | Requisito |
|---|---|
| RF01 | Cadastrar usuário com e-mail e senha |
| RF02 | Autenticar (login), renovar sessão (refresh) e encerrar sessão (logout). Ajustado pelo ADR-0003: login e logout no `auth-server`; refresh fora da v1 |
| RF03 | Trocar a própria senha informando a senha atual |
| RF04 | Criar link curto a partir de uma URL longa, com alias personalizado e expiração opcionais |
| RF05 | Redirecionar `GET /{code}` para o destino |
| RF06 | Listar os próprios links (paginado) e consultar um link |
| RF07 | Remover um link próprio |
| RF08 | Consultar estatísticas de um link próprio: total histórico, total do período, série diária e principais referers |
| RF09 | Expirar links automaticamente na data definida |
| RF10 | Limitar a quantidade de links ativos por usuário |

### 1.4 Requisitos não funcionais

| ID | Atributo | Meta |
|---|---|---|
| RNF01 | Latência do redirect | p99 < 20 ms dentro da aplicação com cache quente; < 50 ms com cache frio ou com Redis degradado |
| RNF02 | Disponibilidade do redirect | não depende de Redis nem de Kafka (degrada, não falha) |
| RNF03 | Consistência de analytics | eventual; clique visível nas estatísticas em segundos |
| RNF04 | Segurança | OWASP API Top 10 como checklist; nenhum 500 provocável por entrada do cliente |
| RNF05 | Escalabilidade | aplicação stateless, escalável horizontalmente |
| RNF06 | Testabilidade | `./mvnw verify` roda tudo só com Docker; cobertura ≥ 80% de linhas fora de config |
| RNF07 | Observabilidade | métricas do redirect por resultado e por cache; health de liveness/readiness |
| RNF08 | Privacidade (LGPD) | minimização: sem IP nem user-agent nos eventos; todo dado coletado tem uso exposto |

### 1.5 Estimativas (ordem de grandeza)

- Alvo de projeto: ~1 milhão de links e ~10 milhões de cliques/mês (~4 eventos/s em média) em uma instância.
- Espaço de códigos com 7 caracteres base62: 62⁷ ≈ 3,5 × 10¹². Com 10 milhões de links, a chance de colisão por
  tentativa é ≈ 2,8 × 10⁻⁶.
- Armazenamento: ~300 B por link → ~300 MB para 1 milhão (com índices). Cliques: ~160 B por evento com os dois
  índices → ~1,6 GB/mês, o que exige política de retenção futura (ver §4).

---

## 2. Visão geral da solução

```text
                 ┌────────────────── Aplicação Spring Boot (stateless, N réplicas) ──────────────────┐
                 │                                                                                    │
 Navegador ──GET/HEAD /{code}──► RateLimit ─► RedirectController ─► LinkResolver ─► Redis (cache-aside)│
                 │                                 │                  └─ miss/erro/disjuntor ─► PostgreSQL
                 │                                 │                                                  │
                 │                                 └─► ClickRecorder.offer(evento)  (só GET; nunca bloqueia)
                 │                                          │ fila em memória limitada                │
                 │                                          ▼                                         │
                 │                                   ClickSender (thread dedicada) ──► Kafka [link-clicks]
                 │                                                                           │        │
                 │                          ClickConsumer (por registro, idempotente) ◄───────┘        │
                 │                                   │          └─ falha definitiva ─► [link-clicks-dlt]
                 │                                   └──► PostgreSQL (click_events)                   │
                 │                                                                                    │
 Cliente API ──/api/v1/**──► Spring Security (JWT Resource Server) ─► controllers ─► serviços ─► BD   │
                 │                                                                                    │
 Operação ──:8081 (só rede interna)──► Actuator completo (health, metrics, prometheus)                │
                 └────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Fluxo do redirecionamento

```text
GET|HEAD /{code}
 1. Rate limit por IP                          → 429 se excedido (fail-open se Redis indisponível)
 2. cache.get(code)                            → hit | miss | erro ou disjuntor aberto (tratado como miss)
 3. miss → links.findByCode(code)
      encontrado e ativo        → SET NX com TTL = min(expiresAt − now, 10 min)
      encontrado e expirado     → SET NX tombstone EXPIRED por 1 h
      encontrado e removido     → SET NX tombstone DELETED por 1 h
      inexistente               → não cacheia (o rate limit contém varreduras)
 4. avaliar estado
      inexistente               → 404
      removido ou expirado      → 410
      ativo                     → segue
 5. se método GET: clickRecorder.offer(evento) → descarta e conta métrica se a fila estiver cheia
 6. 302 Found · Location: <destino> · Cache-Control: private, no-store
```

### 2.2 Fluxo de criação

```text
POST /api/v1/links  (Bearer JWT)
 1. Bean Validation do DTO + regras de domínio (TargetUrl, alias, expiração)
 2. Transação (READ COMMITTED):
      a. select pg_advisory_xact_lock(:ownerId)      (serializa criações do mesmo usuário)
      b. conta links ativos e não expirados          → 409 LINK_QUOTA_EXCEEDED se atingiu a cota
      c. alias informado → INSERT … ON CONFLICT (code) DO NOTHING RETURNING … → 0 linhas = 409 ALIAS_TAKEN
         código gerado  → até 5 tentativas: novo código + INSERT … ON CONFLICT DO NOTHING RETURNING …
      d. esgotou as tentativas → 503 CODE_GENERATION_EXHAUSTED + métrica (sinal para aumentar o tamanho)
 3. 201 Created · Location: /api/v1/links/{code}   (resposta montada a partir do RETURNING)
```

Não há escrita no cache na criação: como códigos inexistentes não são cacheados, não existe entrada a invalidar.

### 2.3 Fluxo de remoção

```text
DELETE /api/v1/links/{code}  (Bearer JWT)
 1. Transação: UPDATE links SET status = 'DELETED', deleted_at = :now
               WHERE code = :code AND owner_id = :owner AND status = 'ACTIVE'   → 0 linhas = 404
    publica LinkDeleted(code) via ApplicationEventPublisher
 2. Após o commit (redirect/infra/LinkCacheInvalidator): SET link:v1:{code} {"status":"DELETED"} PX 3600000
    (incondicional, sobrescreve qualquer entrada ACTIVE)
 3. 204 No Content
```

---

## 3. Decisões

Cada decisão segue o formato: contexto → opções → decisão → consequências.

### D1 — Plataforma e stack

> **Ajustada pelo ADR-0003** (2026-11-02): o `auth-server` acrescenta o starter do Spring Authorization Server e o
> Thymeleaf.

**Contexto.** Requisito do usuário: Java com Spring Boot. O ambiente local tem Java 21 (Corretto) e Maven 3.9 via
mise/Homebrew.

**Opções.** Spring MVC vs WebFlux · Maven vs Gradle · Java 21 vs Java 25.

**Decisão.**

| Item | Escolha | Motivo |
|---|---|---|
| Linguagem | **Java 25 LTS** (Corretto, fixado no projeto via `.mise.toml`) | LTS atual; JEP 491 elimina o *pinning* de virtual threads em `synchronized`; `InetAddress.ofLiteral` simplifica D10 |
| Framework | Spring Boot 4.1.2 | última versão estável (22/10/2026) |
| Web | Spring MVC + virtual threads (`spring.threads.virtual.enabled=true`) | modelo simples, JDBC bloqueante, concorrência alta sem reatividade |
| Build | Maven + wrapper | paridade com o original; ecossistema dominante |
| Persistência | Spring Data JPA (Hibernate 7.4) + `JdbcClient` para SQL específico + Flyway 12 | ver D6 |
| Banco | PostgreSQL 18 | `ON CONFLICT … RETURNING`, advisory locks, `timestamptz` |
| Cache / rate limit | Redis 8 (Valkey é alternativa compatível) | ver D7 e D11 |
| Mensageria | Apache Kafka 4.4 em modo KRaft | ver D8 |
| Segurança | Spring Security 7.1 OAuth2 Resource Server (JWT) | ver D9 |
| Validação | `spring-boot-starter-validation` (Hibernate Validator) | ausente no original |
| Documentação | springdoc-openapi 3.2.0 | Swagger UI no perfil `local` |
| Testes | JUnit 5, AssertJ, Mockito, Testcontainers 2.0, Awaitility, JaCoCo 0.8.16 | ver D14 |

Coordenadas (gerenciadas pelo BOM do Boot 4.1.2, salvo indicação):

| Uso | Coordenada |
|---|---|
| Web | `spring-boot-starter-webmvc` |
| Validação | `spring-boot-starter-validation` |
| JPA | `spring-boot-starter-data-jpa` |
| Flyway | `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql` |
| Driver | `org.postgresql:postgresql` (runtime) |
| Redis | `spring-boot-starter-data-redis` (Lettuce) |
| Kafka | `spring-boot-starter-kafka` |
| Segurança | `spring-boot-starter-security-oauth2-resource-server` |
| Actuator | `spring-boot-starter-actuator` + `io.micrometer:micrometer-registry-prometheus` |
| Tracing (só traceId nos logs) | `spring-boot-micrometer-tracing-brave` + `io.micrometer:micrometer-tracing-bridge-brave`, sem reporter (validar no spike) |
| Docker Compose local | `spring-boot-docker-compose` (optional) |
| Modulith | `org.springframework.modulith:spring-modulith-bom:2.1.2` (**não gerenciado pelo Boot**) → `spring-modulith-api` (compile) e `spring-modulith-starter-test` (test) |
| OpenAPI | `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0` (não gerenciado; fixado) |
| Testes | `spring-boot-starter-test`, `spring-boot-starter-security-oauth2-resource-server-test`, `org.testcontainers:testcontainers-junit-jupiter`, `testcontainers-postgresql`, `testcontainers-kafka`, `testcontainers-toxiproxy`, `org.awaitility:awaitility` |
| Cobertura | `org.jacoco:jacoco-maven-plugin:0.8.16` (não gerenciado; fixado) |

Convenção: identificadores de código em inglês; documentação e mensagens da API em português.

**Consequências.**
- WebFlux foi descartado: exigiria R2DBC e Redis reativo para pouco ganho, já que virtual threads resolvem a
  concorrência do caminho quente.
- Java 25 precisa ser instalado no ambiente (`mise use java@corretto-25` no diretório do projeto). Runtime do container:
  `eclipse-temurin:25-jre`.
- Com virtual threads ligadas, o Boot também executa os listener containers do Kafka nelas — outro motivo para o
  Java 25.

### D2 — Estrutura: monólito modular por funcionalidade

> **Substituída pelo ADR-0002** (2026-11-02) quanto à estrutura física e às dependências entre módulos. As regras
> desta decisão que continuam valendo estão listadas no §7 do ADR-0002.

**Contexto.** O original organizava por domínio, mas os serviços acessavam `SecurityContextHolder` diretamente, havia
código duplicado entre módulos e acoplamento implícito.

**Opções.** Camadas globais (`controller/`, `service/`…) · pacotes por funcionalidade · multi-módulo Maven ·
microsserviços.

**Decisão.** Monólito modular, um pacote raiz por funcionalidade, verificado por Spring Modulith:

```text
br.com.encurtador
├── EncurtadorApplication.java
├── link/                 criação, listagem, remoção, geração de código, validação de destino
│   ├── LinkLookup.java       API pública (usada por redirect e analytics)
│   ├── LinkSnapshot.java     record exposto (id, code, targetUrl, status, expiresAt)
│   ├── LinkDeleted.java      evento de domínio publicado após a remoção
│   ├── api/                  controllers e DTOs
│   ├── application/          LinkService, ShortCodeGenerator
│   ├── domain/               Link, LinkStatus, ShortCode, TargetUrl, Alias, HostClassifier
│   └── infra/                LinkRepository (JPA + JdbcClient)
├── redirect/             resolução do código e resposta 302 (caminho quente)
│   ├── api/                  RedirectController
│   ├── application/          LinkResolver
│   └── infra/                RedisLinkCache, LinkCacheInvalidator (AFTER_COMMIT de LinkDeleted)
├── analytics/            eventos de clique e estatísticas
│   ├── ClickRecorder.java    API pública (usada por redirect)
│   ├── LinkClicked.java      record do evento
│   ├── api/                  StatsController e DTOs
│   ├── application/          StatsService
│   └── infra/                BufferedKafkaClickRecorder, ClickSender, ClickConsumer, ClickEventRepository,
│                             KafkaPublishHealthIndicator
├── identity/             usuários, credenciais, tokens
│   ├── api/                  AuthController, UserController
│   ├── application/          RegistrationService, AuthService, PasswordService, RefreshTokenService
│   ├── domain/               User, RefreshToken, Email, PasswordPolicy
│   └── infra/                repositórios, RefreshTokenPurgeJob
└── shared/               módulo ABERTO (@ApplicationModule(type = OPEN))
    ├── error/                ProblemDetail, exceções base, handler global
    ├── security/             SecurityConfig, CurrentUser, entry point e access denied handler em ProblemDetail
    ├── redis/                RedisGuard (disjuntor compartilhado), customização do Lettuce
    ├── ratelimit/            RateLimiter (Redis + Lua), filtro
    ├── config/               AppProperties (@ConfigurationProperties validadas)
    └── time/                 Clock como bean, gerador de UUIDv7
```

Regras:
- `shared` é declarado módulo aberto (`package-info.java` com `@ApplicationModule(type = ApplicationModule.Type.OPEN)`),
  pois seus subpacotes são consumidos por todos.
- Nos demais módulos, apenas o pacote raiz é API pública; subpacotes são internos (garantido pelo teste
  `ApplicationModules.of(EncurtadorApplication.class).verify()`).
- Dependências permitidas, declaradas em `@ApplicationModule(allowedDependencies = …)`:
  `redirect → link, analytics, shared` · `analytics → link, shared` · `link → shared` · `identity → shared`.
- **Nenhum módulo lê tabelas de outro módulo.** Chaves estrangeiras entre tabelas de módulos diferentes são
  permitidas no banco (integridade), mas o acesso a dados é sempre pela API do módulo dono.
- O módulo dono do cache (`redirect`) é quem o invalida, reagindo ao evento `LinkDeleted`. `link` não conhece o
  formato das chaves do Redis.
- Serviços **não** leem `SecurityContextHolder`: o controller extrai o `UserId` do `Jwt` e o passa explicitamente.
- Tempo vem de um `java.time.Clock` injetado — nada de `LocalDateTime.now()` espalhado.
- Controllers são finos; a fronteira transacional fica nos serviços de aplicação.

**Consequências.** Fronteiras explícitas e testáveis sem o custo de multi-módulo. Se um módulo precisar virar serviço
(ex.: `analytics`), a fronteira já existe. `spring-modulith-api` é uma dependência de compilação pequena (só
anotações).

### D3 — Geração do código curto

**Contexto.** O original truncava um SHA-256 em 6 hex (24 bits) e checava `exists` antes de `save`, sujeito a colisões
e a corrida.

**Opções.**

| Opção | Único por construção | Não enumerável | Complexidade | Veredito |
|---|---|---|---|---|
| A. Hash truncado + retry | não | sim | média | rejeitada (colisões crescem, mesma URL no mesmo instante) |
| B. Base62 de sequência do BD | sim | **não** (sequencial, vaza volume) | baixa | rejeitada |
| C. Sequência + permutação (Feistel/Sqids) | sim | parcialmente (ofuscação, não segredo) | média | alternativa válida |
| D. Aleatório criptográfico base62 + `UNIQUE` + retry | probabilístico | sim | baixa | **escolhida** |
| E. Serviço de chaves pré-geradas (KGS) | sim | sim | alta | exagero para a escala |

**Decisão (D).**
- Alfabeto base62 `[0-9A-Za-z]`, comprimento 7 (`app.links.code-length`), gerado com uma instância compartilhada de
  `SecureRandom`.
- A unicidade é garantida pelo índice `UNIQUE` em `links.code`, nunca por consulta prévia. A inserção usa
  `INSERT … ON CONFLICT (code) DO NOTHING RETURNING …` via `JdbcClient`: não aborta a transação do PostgreSQL e permite
  repetir dentro da mesma transação (necessário para a cota, D5).
- A criação de `Link` é **sempre** via `JdbcClient` (nunca `save()` do JPA no mesmo fluxo); a resposta é montada a
  partir do `RETURNING`, de modo que nenhuma entidade obsoleta fica no contexto de persistência.
- Até 5 tentativas; esgotar gera 503 e a métrica `shortener.code.exhausted`.
- Códigos diferenciam maiúsculas de minúsculas (coluna com `collate "C"`).
- **Alias personalizado** (opcional): regex `[A-Za-z0-9_-]{4,32}`, compartilha a coluna `code`; conflito → 409. Lista
  de palavras reservadas comparada sem diferenciar caixa: `api`, `actuator`, `swagger-ui`, `v3`, `docs`, `health`,
  `livez`, `readyz`, `error`, `login`, `logout`, `admin`, `static`, `assets`.

**Consequências.** Sem coordenação entre instâncias, códigos imprevisíveis e colisão desprezível na escala alvo.
Códigos curtos **não são controle de acesso**: com 10⁷ links, um palpite aleatório acerta 1 em ~350 mil; o rate limit
(D11) torna a varredura cara, mas não impossível. Isso fica documentado para os usuários.

### D4 — Rota e semântica do redirecionamento

**Opções de status.** 301/308 (permanente) vs 302/307 (temporário).

**Decisão.**
- Rota na raiz: `GET /{code:[A-Za-z0-9_-]{4,32}}`. Toda a API fica em `/api/v1/**`. O padrão exclui `.`, então
  `favicon.ico`, `swagger-ui.html` etc. não são capturados. `/error` e `/actuator` casam o padrão, mas não conflitam:
  o literal `/error` vence o template, o mapeamento do actuator tem precedência, e ambos são palavras reservadas.
- **302 Found** com `Cache-Control: private, no-store`. Redirecionamentos permanentes são cacheados por navegadores e
  proxies sem prazo, o que impediria contar cliques e tornaria remoção e expiração ineficazes. O 307 é equivalente para
  GET; o 302 é o mais universal.
- O destino vai no `Location` já normalizado em ASCII, o que elimina injeção de cabeçalho.
- Respostas de erro: 404 (inexistente), 410 Gone (expirado ou removido), 429 (rate limit), sempre em
  `application/problem+json`. Página HTML amigável fica para o futuro.
- `HEAD` é respondido (o `@GetMapping` atende HEAD automaticamente) e liberado na segurança junto com GET (D9), mas
  **não** registra clique.
- O redirect nunca espera o analytics nem falha por indisponibilidade do Redis.

**Consequências.** Cada clique chega ao servidor (custo aceito: é o que torna o analytics e o ciclo de vida
confiáveis). Crawlers de pré-visualização (WhatsApp, Slack) contam como cliques — limitação conhecida (§4).

### D5 — Ciclo de vida do link

**Decisão.**
- Estados persistidos: `ACTIVE` e `DELETED`. **`EXPIRED` é derivado** (`expires_at <= now`) e nunca gravado no
  caminho de leitura — elimina a classe de bug P3.
- Expiração: `expiresAt` opcional na criação; padrão `app.links.default-ttl` = 30 dias; máximo
  `app.links.max-ttl` = 365 dias; mínimo de 1 minuto no futuro. Todo link expira (limita armazenamento e abuso).
- Remoção é *soft delete* (`status = DELETED`, `deleted_at`). O código fica reservado para sempre (corrige P11), e o
  redirect devolve 410.
- **Destino imutável.** Não há edição de URL: evita o golpe de trocar o destino depois que o link foi compartilhado.
  Para mudar, cria-se outro link.
- Cota: `app.links.max-active-per-user` = 100 links ativos e não expirados (corrige P12). A criação roda em
  READ COMMITTED (padrão; **não elevar o isolamento**, pois em REPEATABLE READ a contagem usaria o snapshot anterior
  ao lock e a corrida voltaria) e obtém `select pg_advisory_xact_lock(:ownerId)` antes de contar. O advisory lock
  não toca a tabela `users` (que pertence a `identity`) e não conflita com os locks de chave estrangeira de login e
  refresh. É o único uso de advisory lock da aplicação; se surgir outro, migrar para a forma de duas chaves
  (namespace + id).

**Consequências.** Linhas removidas permanecem no banco (custo pequeno, e preservam a integridade referencial dos
cliques tardios). Listagens filtram `DELETED`.

### D6 — Persistência e modelo de dados

> **Ajustada pelo ADR-0003** (D7, D8): `users` vira `accounts`, sem senha nem e-mail; credenciais e autorizações vão
> para o schema `auth` do `auth-server`.

**Opções.** JPA puro · Spring Data JDBC · jOOQ · JPA + `JdbcClient` pontual.

**Decisão.** JPA para leitura e CRUD simples das entidades; `JdbcClient` para as instruções que o JPA expressa mal:
`INSERT … ON CONFLICT … RETURNING` (links), inserção idempotente de cliques, rotação de refresh token com
`UPDATE … RETURNING` e agregações de estatísticas. O `JdbcClient` participa da mesma transação do
`JpaTransactionManager` (o `HibernateJpaDialect` expõe a conexão JDBC). Regras:
- Flyway versiona o schema; `spring.jpa.hibernate.ddl-auto=validate` (nunca `create-drop`, o que elimina P10).
  Uma migração por fase de implementação; **migração aplicada nunca é editada**.
- `spring.jpa.open-in-view=false`; datas como `Instant` em colunas `timestamptz`; `hibernate.jdbc.time_zone=UTC`.
- Todo instante (`created_at`, `updated_at`, `revoked_at`, `deleted_at`, `received_at`) vem do `Clock` da aplicação.
  As colunas não têm `default now()`: um valor esquecido falha no `not null` em vez de gravar o relógio do banco, que
  divergiria do `Clock` fixo dos testes.
- Enums como `varchar` com `CHECK`, nunca ordinal.
- **Sem associações bidirecionais.** `Link` guarda `ownerId` (`Long`), sem `@ManyToOne User`, o que evita lazy
  loading e entidades desanexadas no contexto de segurança.
- `event_id` dos cliques é UUIDv7 gerado na aplicação (ordenado no tempo: evita inserções aleatórias no índice da PK
  com ~10 milhões de linhas por mês).

Schema consolidado (dividido em `V1`–`V3`, uma migração por fase):

```sql
-- V1__identity.sql (F1)
create table users (
    id            bigint generated always as identity primary key,
    email         varchar(254) not null,
    password_hash varchar(255) not null,               -- comporta Argon2 num upgrade do DelegatingPasswordEncoder
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    constraint ux_users_email unique (email),
    constraint ck_users_email_lower check (email = lower(email))
);

create table refresh_tokens (
    id                bigint generated always as identity primary key,
    user_id           bigint      not null references users (id),
    family_id         uuid        not null,
    token_hash        char(64)    not null,            -- SHA-256 hex; o token em claro nunca é guardado
    expires_at        timestamptz not null,            -- validade deste token (≤ 7 dias)
    family_expires_at timestamptz not null,            -- validade absoluta da sessão (30 dias desde o login)
    revoked_at        timestamptz,
    created_at        timestamptz not null,
    constraint ux_refresh_tokens_hash unique (token_hash)
);
create index ix_refresh_tokens_user   on refresh_tokens (user_id);
create index ix_refresh_tokens_family on refresh_tokens (family_id);

-- V2__links.sql (F2)
create table links (
    id           bigint generated always as identity primary key,
    code         varchar(32) collate "C" not null,
    target_url   varchar(2048) not null,
    owner_id     bigint        not null references users (id),
    custom_alias boolean       not null,
    status       varchar(16)   not null check (status in ('ACTIVE', 'DELETED')),
    created_at   timestamptz   not null,
    expires_at   timestamptz   not null,
    deleted_at   timestamptz,
    constraint ux_links_code unique (code),
    constraint ck_links_deleted check ((status = 'DELETED') = (deleted_at is not null)),
    constraint ck_links_expiry  check (expires_at > created_at)
);
create index ix_links_owner_created on links (owner_id, created_at desc);
create index ix_links_owner_active  on links (owner_id, expires_at) where status = 'ACTIVE';   -- cota

-- V3__click_events.sql (F4)
create table click_events (
    event_id      uuid         primary key,            -- UUIDv7; idempotência do consumidor
    link_id       bigint       not null references links (id),
    occurred_at   timestamptz  not null,               -- instante do redirect, não do consumo (P4)
    referrer_host varchar(255),
    received_at   timestamptz  not null
);
create index ix_click_events_link_time on click_events (link_id, occurred_at);
```

**Consequências.** SQL explícito onde importa e conveniência do JPA no restante. Testes de repositório rodam contra
PostgreSQL real (Testcontainers), nunca H2.

### D7 — Cache de resolução (Redis)

**Opções.** `@Cacheable` (abstração do Spring) · cache-aside explícito · cache local (Caffeine) · sem cache.

**Decisão.** Cache-aside explícito, de propriedade do módulo `redirect`.
- Chave `link:v1:{code}` → JSON `{linkId, targetUrl, status, expiresAt}`. O prefixo versionado permite mudar o
  formato sem colisão.
- **Preenchimento após miss sempre com `SET key value NX PX ttl`** (nunca sobrescreve).
  - Ativo: TTL = `min(expiresAt − now, app.cache.max-ttl = 10 min)`. O `expiresAt` também é verificado no hit (defesa
    em profundidade).
  - Expirado: tombstone `{status: EXPIRED}` por 1 h (a expiração é irreversível, pois não há edição).
  - Removido: tombstone `{status: DELETED}` por 1 h.
  - **Inexistente não é cacheado.** Uma varredura aleatória não repete códigos, então cachear o 404 só gravaria lixo
    no Redis; o lookup por índice único custa frações de milissegundo e a varredura é contida pelo rate limit (D11).
- **Invalidação na remoção:** o `LinkCacheInvalidator` (`@TransactionalEventListener(phase = AFTER_COMMIT)` sobre
  `LinkDeleted`) grava o tombstone **incondicional** `SET link:v1:{code} {"status":"DELETED"} PX 3600000`, em vez de
  `DEL`. Combinado com o `NX` do preenchimento, nenhuma intercalação reinstala `ACTIVE`:
  - leitor grava `ACTIVE` antes do tombstone → o tombstone sobrescreve;
  - leitor tenta gravar `ACTIVE` depois do tombstone → o `NX` falha.
- O listener AFTER_COMMIT roda em `afterCompletion`, onde o Spring apenas loga exceções; por isso ele captura
  qualquer exceção explicitamente e incrementa `shortener.cache.errors{op=invalidate}`. Se o tombstone falhar, a
  staleness máxima é `app.cache.max-ttl` (10 min).
- **Fail-open com orçamento de latência** (vale também para o rate limit, D11):
  - `spring.data.redis.timeout=100ms`, `spring.data.redis.connect-timeout=200ms`;
  - `LettuceClientConfigurationBuilderCustomizer` com `ClientOptions.DisconnectedBehavior.REJECT_COMMANDS`, para que
    comandos não fiquem enfileirados enquanto a conexão está caída;
  - `RedisGuard` (disjuntor simples em `shared/redis`): após 5 falhas consecutivas, o Redis é ignorado por 5 s; depois
    uma chamada de teste decide se fecha (meio-aberto). Métrica `shortener.redis.circuit{state}`;
  - qualquer erro, timeout ou disjuntor aberto vira miss e o redirect segue pelo banco
    (`spring.datasource.hikari.connection-timeout=2s`, para não enfileirar requisições por 30 s).
- Serialização com `StringRedisTemplate` + `JsonMapper` (Jackson 3) explícito: simples de depurar com `redis-cli`.
- O Redis local roda **sem persistência** (`--save "" --appendonly no`): é cache e contador, não fonte de verdade.

**Consequências.** `@Cacheable` foi descartado porque não expressa TTL por entrada, tombstones, `SET NX` e fail-open
com clareza. Um L1 local (Caffeine) fica como otimização futura.

### D8 — Analytics de cliques

**Opções.**

| Opção | Impacto no redirect | Durabilidade | Peso operacional | Veredito |
|---|---|---|---|---|
| A. INSERT síncrono no PostgreSQL | alto (escrita no caminho quente) | alta | baixo | rejeitada |
| B. Fila em memória + Kafka | nenhum | igual a D até o envio; depois, a do Kafka (RF 1 no local) | médio | **escolhida** |
| C. Redis Streams | baixo | média | baixo | rejeitada: Redis é fail-open (D7) e não deve ser fonte de verdade |
| D. Fila em memória + INSERT em lote | nenhum | igual a B até o insert; depois, a do PostgreSQL | mínimo | plano B se o Kafka for considerado excessivo |
| E. Transactional outbox | alto (escrita por clique) | alta | médio | rejeitada |

A principal janela de perda (a fila em memória) é idêntica em B e D. **A vantagem real do Kafka é absorver
indisponibilidade do PostgreSQL e atender ao objetivo de estudo de mensageria**, não durabilidade nem carga.

**Decisão (B), com desacoplamento total do request:**
- `ClickRecorder.offer(LinkClicked)` coloca o evento numa fila em memória limitada (`ArrayBlockingQueue`, capacidade
  `app.analytics.buffer-capacity` = 10 000). Se a fila estiver cheia, descarta e incrementa
  `shortener.clicks.dropped`. **A thread do request nunca toca o cliente Kafka**, o que corrige P2.
- `ClickSender` (`SmartLifecycle`, thread dedicada) drena a fila com `drainTo` e publica com `KafkaTemplate`,
  tratando os futures de forma assíncrona (nunca `.get()`). Falhas são contadas (`shortener.clicks.publish.failed`) e
  logadas; não há retry infinito. Sua fase de lifecycle é **menor** que
  `WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE`, para parar **depois** do servidor web e drenar os cliques
  das requisições ainda em andamento (com prazo).
- **Producer:** `acks=all`, `enable.idempotence=true`, `max.block.ms=2000` (o padrão de 60 s travaria o ClickSender sem
  metadados), `delivery.timeout.ms=30000`, `linger.ms=20`, `compression.type=lz4`.
- `spring.kafka.admin.operation-timeout=5s`: a aplicação sobe mesmo com o broker fora.
- Tópico `link-clicks` (3 partições; RF 1 no local), chave = `linkId`.
- Evento `LinkClicked {v: 1, eventId: UUIDv7, linkId, occurredAt: Instant, referrerHost?}` em JSON sem cabeçalho de
  tipo; o consumidor usa tipo fixo com `ErrorHandlingDeserializer`.
- **Consumer por registro** (na carga alvo, ~4 eventos/s, consumo em lote não traz ganho e complica o roteamento de
  erros): `INSERT … ON CONFLICT (event_id) DO NOTHING` (idempotente diante do *at-least-once*).
  `DefaultErrorHandler(new DeadLetterPublishingRecoverer(template, destino), new FixedBackOff(1000, 2))`, com
  falha de desserialização e `DataIntegrityViolationException` marcadas como não repetíveis.
- DLT com destino **explícito** `link-clicks-dlt` (resolvedor próprio, sem depender do sufixo padrão da versão do
  Spring Kafka), 3 partições, porque o recoverer publica na mesma partição do registro original.
- Cliques tardios de links removidos são aceitos (a linha existe por causa do soft delete) e não aparecem nas
  estatísticas.
- Privacidade: não se guarda IP nem user-agent; do referer, só o host — e ele é exposto em `topReferrers` (RNF08: todo
  dado coletado tem uso). `occurredAt` vem do `Clock` no momento do redirect.
- **Estatísticas** (intervalo de até 90 dias, `tz` validado como `ZoneId`, padrão `app.analytics.default-zone` =
  `America/Sao_Paulo`):
  - `from`/`to` (datas locais) são convertidos em instantes no fuso pedido, e o filtro é
    `occurred_at >= :fromInstant AND occurred_at < :toInstant`, para aproveitar `ix_click_events_link_time`;
  - série diária agrupada por `date_trunc('day', occurred_at at time zone :tz)`, com dias sem clique preenchidos com
    zero na aplicação;
  - `totalClicks` (histórico), `periodClicks` (no intervalo) e `topReferrers` (10 hosts mais frequentes no intervalo;
    ausência de referer agregada como `null`/"direto").

**Consequências.** Perda de eventos ainda na fila em caso de crash é aceita (analytics é *best-effort*,
documentado). O Kafka é o componente mais pesado da stack; a opção D permanece como plano B, trocável atrás da
interface `ClickRecorder` sem tocar no redirect.

### D9 — Autenticação e autorização

> **Substituída pelo ADR-0003** (2026-11-02), exceto a tabela de autorização das rotas e as respostas 401/403 em
> ProblemDetail, que continuam. O ADR-0002 (D3, D5) passa a declaração das rotas públicas e das regras de limite para
> os módulos, e a tradução do `sub` para o `UserIdResolver`.

**Opções.** Filtro JWT customizado (original) · Spring Security OAuth2 Resource Server com JWT emitido pela própria
aplicação · IdP externo (Keycloak) · sessão com cookie.

**Decisão.** Resource Server do Spring Security, com tokens emitidos pela própria aplicação. O filtro customizado
(fonte de P5, P8 e do triplo parsing) desaparece.

**Access token**
- Emissão: `NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build()`, com
  `JwsHeader.with(MacAlgorithm.HS256)` explícito em cada `encode`.
- Claims: `sub` = id do usuário (não o e-mail, que pode mudar), `iss`, `aud = "encurtador-api"`, `iat`,
  `exp = iat + 15 min`. Instantes vêm do `Clock` (corrige P8).
- Validação: `NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()`, com validadores de
  emissor, audiência e `typ`, e um `JwtTimestampValidator(Duration.ofSeconds(5))` que recebe **o mesmo bean `Clock`**
  usado na emissão (`setClock`). Sem isso, testes com `Clock` fixo recebem 401 em toda requisição.
- `APP_JWT_SECRET` é Base64 de pelo menos 32 bytes aleatórios; o tamanho é verificado **depois de decodificar**, no
  startup (fail fast).
- A F0 inclui um teste de ida e volta encode → decode (houve regressão de `typ` no Spring Security 7.0.0, corrigida no
  7.0.1).

**Refresh token**
- Opaco: 256 bits aleatórios em base64url, guardado só como SHA-256. Validade de 7 dias por token, limitada pela
  validade absoluta da família (`family_expires_at` = 30 dias desde o login).
- Rotação, numa única transação, **sem lançar exceção dentro dela** (o serviço devolve
  `RefreshOutcome {ISSUED, INVALID, REUSED}` — lançar dentro da transação faria o rollback desfazer a revogação, a mesma
  classe de bug do P3):
  1. `UPDATE refresh_tokens SET revoked_at = :now WHERE token_hash = :h AND revoked_at IS NULL AND expires_at > :now
     RETURNING user_id, family_id, family_expires_at`.
  2. Se uma linha voltar: emite o novo par na mesma família, com `expires_at = min(:now + 7d, family_expires_at)` →
     `ISSUED`.
  3. Se nenhuma voltar, busca o token pelo hash:
     - inexistente ou expirado → `INVALID`;
     - já revogado (reuso) → `UPDATE … SET revoked_at = :now WHERE family_id = :f AND revoked_at IS NULL` → `REUSED`.
  4. Após o commit, o controller converte `INVALID` e `REUSED` em 401.
- Consequência aceita: duas renovações concorrentes com o mesmo token revogam a família; o cliente deve serializar o
  refresh.
- `RefreshTokenPurgeJob` (`@Scheduled`, diário) apaga tokens expirados há mais de 1 dia.
- **Logout** revoga o refresh token (idempotente). **Troca de senha** exige a senha atual e revoga todos os refresh
  tokens do usuário (corrige S3). O access token vigente expira em no máximo 15 minutos (aceito).

**Credenciais**
- `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (bcrypt por padrão, com upgrade possível).
- A senha é normalizada para NFC e precisa ter de 8 a 64 caracteres **e no máximo 72 bytes em UTF-8**, verificados por
  um validador próprio (`@MaxUtf8Bytes(72)`) no cadastro e na troca. O Spring Security atual lança
  `IllegalArgumentException` ao codificar senha acima de 72 bytes (correção da CVE-2025-22228); sem o validador, uma
  senha com acentos ou emojis geraria 500.
- No login, entrada acima de 72 bytes recebe o mesmo 401 genérico, sem chamar o encoder.
- E-mail normalizado (trim + minúsculas), `@Email`, até 254 caracteres.
- **Login** via `AuthenticationManager`/`DaoAuthenticationProvider`; e-mail inexistente e senha errada devolvem o mesmo
  401 genérico (corrige S2). O provider já mitiga diferença de tempo para usuário inexistente.
- **Cadastro** devolve 409 para e-mail duplicado. É uma enumeração aceita conscientemente, mitigada por rate limit; a
  alternativa (verificação por e-mail) está fora do escopo.

**Autorização das rotas**

| Rota | Acesso |
|---|---|
| `GET` e `HEAD` `/*` (um segmento; o padrão do controller filtra o resto) | público |
| `POST /api/v1/auth/{register,login,refresh,logout}` | público (refresh e logout exigem o refresh token no corpo) |
| `/api/v1/**` (demais) | Bearer JWT |
| `/livez`, `/readyz` (`management.endpoint.health.probes.add-additional-paths=true`) | público, sem detalhes |
| Actuator completo | só na porta de gestão `management.server.port=8081`, publicada apenas em `127.0.0.1` ou rede interna |
| `/swagger-ui/**`, `/v3/api-docs/**` | público só no perfil `local` (corrige P6) |
| `/error` | público |
| qualquer outra | `denyAll()` |

- **Posse:** consultas sempre filtram por `owner_id`. Link de outro usuário devolve **404**, sem revelar que existe.
- **Erros de segurança em ProblemDetail** (corrige P5, P7, P9, S4): o `BearerTokenAuthenticationEntryPoint` só
  escreve status e `WWW-Authenticate`, sem corpo. Por isso, `ProblemDetailAuthenticationEntryPoint` delega a ele (para
  o cabeçalho) e escreve o corpo RFC 9457; `ProblemDetailAccessDeniedHandler` faz o equivalente para 403.
- CSRF desabilitado (API stateless com bearer, sem cookies). CORS com allowlist configurável, vazia por padrão.

**Consequências.** Menos código de segurança próprio e comportamento padrão bem testado. HS256 exige que quem valida
conheça o segredo; se surgirem outros serviços, migrar para RS256/JWKS (o Resource Server suporta sem mudar os
controllers). Um IdP externo fica como evolução.

### D10 — Validação de entrada e do destino

> **Ajustada pelo ADR-0003** (D9): o `HostClassifier` também recusa o host do `auth-server`.

**Decisão.**
- DTOs são records com Bean Validation; `@Valid` gera 400 com a lista de campos inválidos (corrige S1).
- O destino é o value object de domínio `TargetUrl` (não só anotação), com estas regras:
  1. `trim`; rejeita entrada com mais de 2048 caracteres ou com caractere de controle/espaço. Caracteres fora do
     RFC 3986 precisam vir percent-encoded (400 com mensagem clara).
  2. Parse estrito com `java.net.URI`; precisa ser absoluta.
  3. Esquema `http` ou `https`. Isso rejeita `javascript:`, `data:`, `file:` e `ftp:`.
  4. Host obrigatório e **somente ASCII**: domínios internacionais devem vir em Punycode (`xn--…`). Motivo: o
     `java.net.URI` não aceita host não ASCII (`getHost()` devolve `null`) e `IDN.toASCII` implementa IDNA2003, que
     diverge dos navegadores (UTS-46) — o destino gravado poderia ser outro. Normalização: minúsculas e remoção de um
     ponto final.
  5. Sem *userinfo* (`https://banco.com@golpe.com`).
  6. Porta, se houver, entre 1 e 65535.
  7. Classificação do host, **sem DNS** (`HostClassifier`):
     - **(a) IPv6 literal** (`[…]`): parse com `InetAddress.ofLiteral` (Java 22+). Rejeita `::`, `::1`, `fe80::/10`,
       `fc00::/7`, `ff00::/8`, zone id, e IPv4 mapeado, compatível ou NAT64 (`64:ff9b::/96`) cujo IPv4 seja bloqueado.
     - **(b) Último rótulo numérico ou iniciado por `0x`** (regra "ends in a number" do WHATWG, que os navegadores usam
       para interpretar `2130706433` e `0177.0.0.1` como 127.0.0.1): só se aceita IPv4 canônico (4 octetos decimais sem
       zero à esquerda) fora de 0/8, 10/8, 100.64/10, 127/8, 169.254/16, 172.16/12, 192.168/16, 198.18/15, 224/4 e
       240/4. Qualquer outra forma numérica é rejeitada.
     - **(c) Nome:** rejeita `localhost`, `*.localhost` (RFC 6761) e o host de `app.base-url` com seus subdomínios
       (evita loops e cadeias de encurtadores).
  8. Armazenamento em `toASCIIString()`; o limite de 2048 é verificado **também** sobre o valor final normalizado
     (o percent-encoding expande a entrada e estouraria `varchar(2048)`).
- Bloquear IPs privados é higiene contra links que atacam a rede do visitante, não uma barreira: um nome DNS pode
  resolver para 192.168.0.1. **Não há SSRF** porque o servidor nunca acessa o destino. Safe Browsing/blocklist fica
  para o futuro, via interface `TargetUrlPolicy`.
- Paginação: `page ≥ 0`, `size` de 1 a 100, ordenação fixa por `createdAt desc`.
- Datas: `expiresAt` em ISO-8601 com offset; intervalo de estatísticas de no máximo 90 dias.

**Consequências.** Toda entrada inválida devolve 4xx previsível; o 500 fica reservado para falhas reais. Usuários
precisam informar domínios internacionais em Punycode (limitação documentada; suporte UTS-46 com ICU4J é evolução).

### D11 — Proteção contra abuso

> **Ajustada pelo ADR-0002** (D5: regras declaradas pelos módulos, filtro registrado pelo `app` fora da cadeia de
> segurança) **e pelo ADR-0003** (D9: limites de login, cadastro, refresh e troca de senha passam para o `auth-server`).

**Opções.** Bucket4j com backend Redis · janela fixa própria em Redis · rate limit apenas no proxy/gateway.

**Decisão.** Janela fixa própria em Redis, com `INCR` + `PEXPIRE` atômicos via script Lua de poucas linhas, protegido
pelo mesmo `RedisGuard` do D7.

| Alvo | Chave | Limite padrão |
|---|---|---|
| `POST /api/v1/auth/login` | IP | 10/min |
| `POST /api/v1/auth/login` | e-mail normalizado | 5/15 min |
| `POST /api/v1/auth/register` | IP | 10/h |
| `POST /api/v1/auth/refresh` | IP | 30/min |
| `PUT /api/v1/users/me/password` | usuário | 5/15 min (força bruta da senha atual com token roubado) |
| `POST /api/v1/links` | usuário | 30/min |
| `GET`/`HEAD` `/{code}` | IP | 600/min |

- Chave Redis: `rl:{alvo}:{chave}:{janela}`. IPv6 agregado por /64.
- Excedido: 429 com `Retry-After`, escrito **diretamente pelo filtro** em ProblemDetail (o `@RestControllerAdvice` não
  atua em filtros). Redis indisponível: *fail-open* + métrica `shortener.ratelimit.failopen`.
- O filtro roda **depois** do `BearerTokenAuthenticationFilter` (os limites por usuário precisam do `sub`). Se for
  `@Component`, um `FilterRegistrationBean#setEnabled(false)` impede que o servlet container o execute uma segunda vez.
- IP do cliente: `request.getRemoteAddr()`. Atrás de proxy, usar `server.forward-headers-strategy=native` com
  `server.tomcat.remoteip.internal-proxies` restrito aos proxies confiáveis — nunca `framework`, cujo
  `ForwardedHeaderFilter` confia em qualquer origem.
- Limitação local documentada: no Docker Desktop, `getRemoteAddr()` pode devolver o gateway, e todos os clientes
  compartilham o mesmo balde.
- A cota de links ativos (D5) complementa o rate limit.

**Consequências.** Sem dependência extra e fácil de testar. A janela fixa permite rajadas de até 2× na virada da
janela (aceito). Bucket4j é a evolução natural se for preciso token bucket.

### D12 — Contrato da API e erros

> **Ajustada pelo ADR-0003** (D10): saem `/api/v1/auth/*` e `/api/v1/users/me/password`; os endpoints de protocolo do
> `auth-server` usam o formato de erro do OAuth.

**Decisão.**
- Versionamento por URL (`/api/v1`). Corpos sem `HttpStatus` embutido (o status é o do HTTP).
- Erros em RFC 9457 (`application/problem+json`) com os campos `type`, `title`, `status`, `detail`, `instance`, mais
  `code` estável legível por máquina (ex.: `LINK_EXPIRED`) e `errors[]` nas falhas de validação.
- Um `@RestControllerAdvice` global (estendendo `ResponseEntityExceptionHandler`) com fallback 500 genérico: sem stack
  trace no corpo e com `traceId` no log. Erros gerados em filtros (401, 403, 429) usam os componentes do D9 e do D11.
- Paginação com envelope próprio e estável: `PageResponse<T>(content, page, size, totalElements, totalPages)`.

| Método | Caminho | Auth | Sucesso | Erros principais |
|---|---|---|---|---|
| POST | `/api/v1/auth/register` | — | 201 | 400, 409 `EMAIL_TAKEN`, 429 |
| POST | `/api/v1/auth/login` | — | 200 `TokenResponse` | 400, 401 `INVALID_CREDENTIALS`, 429 |
| POST | `/api/v1/auth/refresh` | refresh token | 200 `TokenResponse` | 401 `INVALID_REFRESH_TOKEN`, 429 |
| POST | `/api/v1/auth/logout` | refresh token | 204 | — (idempotente) |
| PUT | `/api/v1/users/me/password` | Bearer | 204 | 400, 401, 422 `WRONG_CURRENT_PASSWORD`, 429 |
| POST | `/api/v1/links` | Bearer | 201 `LinkResponse` + `Location` | 400, 409 `ALIAS_TAKEN` / `LINK_QUOTA_EXCEEDED`, 429, 503 |
| GET | `/api/v1/links?page&size` | Bearer | 200 `PageResponse<LinkResponse>` | 400 |
| GET | `/api/v1/links/{code}` | Bearer | 200 `LinkResponse` | 404 |
| DELETE | `/api/v1/links/{code}` | Bearer | 204 | 404 |
| GET | `/api/v1/links/{code}/stats?from&to&tz` | Bearer | 200 `LinkStatsResponse` | 400, 404 |
| GET/HEAD | `/{code}` | — | 302 | 404, 410, 429 |

Formatos:

```jsonc
// TokenResponse
{ "tokenType": "Bearer", "accessToken": "…", "expiresIn": 900, "refreshToken": "…" }

// LinkResponse
{ "code": "aZ3kP9q", "shortUrl": "http://localhost:8080/aZ3kP9q", "targetUrl": "https://exemplo.com/a",
  "status": "ACTIVE", "createdAt": "2026-10-31T12:00:00Z", "expiresAt": "2026-11-30T12:00:00Z" }

// LinkStatsResponse
{ "code": "aZ3kP9q", "totalClicks": 42, "periodClicks": 30,
  "from": "2026-10-01", "to": "2026-10-31", "timezone": "America/Sao_Paulo",
  "daily": [ { "date": "2026-10-30", "clicks": 12 } ],
  "topReferrers": [ { "host": "t.co", "clicks": 9 }, { "host": null, "clicks": 7 } ] }
```

`LinkResponse.status` pode ser `ACTIVE` ou `EXPIRED` (derivado). Links removidos não são listados.

### D13 — Observabilidade

> **Ajustada pelo ADR-0003** (D2): o `auth-server` segue a mesma observabilidade e nunca registra credenciais nem
> códigos.

**Decisão.**
- **Porta de gestão separada** (`management.server.port=8081`, publicada só internamente) com `health`, `info`,
  `metrics` e `prometheus`. Na porta pública ficam apenas `/livez` e `/readyz`.
- **Health:** a readiness inclui só o PostgreSQL. O Spring Boot não tem health indicator de Kafka; um
  `KafkaPublishHealthIndicator` próprio, baseado no último envio bem-sucedido do `ClickSender`, aparece no health
  detalhado sem afetar a readiness (RNF02). O health do Redis entra no status raiz de `/actuator/health`; por isso o
  `HEALTHCHECK` do container usa `/livez`.
- **Métricas Micrometer:**
  - `shortener.redirect` (timer com tags `outcome` = found/not_found/gone e `cache` = hit/miss/error/bypassed);
  - `shortener.links.created`, `shortener.code.collisions`, `shortener.code.exhausted`;
  - `shortener.clicks.enqueued`, `shortener.clicks.dropped`, `shortener.clicks.published`,
    `shortener.clicks.publish.failed`;
  - `shortener.cache.errors{op}`, `shortener.redis.circuit{state}`, `shortener.ratelimit.rejected`,
    `shortener.ratelimit.failopen`.
  - **Regra:** nunca usar `code`, e-mail ou URL como tag (cardinalidade e privacidade).
- **Logs:** JSON estruturado (`logging.structured.format.console=ecs`) fora do perfil `local`, com `traceId`/`spanId`
  do Micrometer Tracing. Nunca logar senha, token nem URL de destino completa (query strings podem carregar segredos):
  loga-se só o código e o host.
- `server.shutdown=graceful`.

### D14 — Estratégia de testes

> **Ajustada pelo ADR-0002** (D6, D7: testes de integração no `app`, gate no módulo `coverage`) **e pelo ADR-0003**
> (D12: cenários redistribuídos; 8 e 12 ficam fora da v1, sem refresh token).

**Decisão.** Pirâmide com infraestrutura real nos testes de integração:

| Nível | Ferramentas | Cobre |
|---|---|---|
| Unitário | JUnit 5, AssertJ, Mockito, `Clock` fixo | `ShortCodeGenerator`, `TargetUrl`/`HostClassifier` (tabela parametrizada), `Alias`, regras de `Link`, política de senha, rate limiter, `RedisGuard`, emissão de token |
| Slice web | `@WebMvcTest` + `@Import(SecurityConfig.class)` + `@MockitoBean RateLimiter` | validação → 400, 401/403/404 em ProblemDetail, contratos JSON |
| Slice de dados | `@DataJpaTest` + Testcontainers PostgreSQL | migrações Flyway, constraints, `ON CONFLICT`, consultas de posse e agregação |
| Integração | `@SpringBootTest` + Testcontainers (PostgreSQL, Redis, Kafka, Toxiproxy) com `@ServiceConnection`, Awaitility | fluxos ponta a ponta e concorrência (abaixo) |
| Arquitetura | Spring Modulith `verify()` | fronteiras e dependências permitidas entre módulos, ausência de ciclos |

Cenários obrigatórios de integração:
1. register → login → criar → redirect 302 → clique aparece nas estatísticas.
2. Remoção com cache quente → próximo redirect devolve 410 (regressão de P1).
3. Expiração com `Clock` controlável → 410, sem escrita no banco (regressão de P3).
4. Redis parado → redirect continua 302 (RNF02).
5. Redis "pendurado" (Toxiproxy, toxic `timeout`) → com o disjuntor aberto, p99 do redirect < 50 ms.
6. Kafka indisponível → redirect continua 302 e dentro do orçamento de latência (regressão de P2).
7. Evento duplicado no Kafka → um único registro (idempotência); mensagem envenenada vai para `link-clicks-dlt`.
8. Refresh: rotação funciona; reuso revoga a família; token expirado → 401.
9. Token expirado ou adulterado → 401, nunca 500 (regressão de P5).
10. `occurredAt` gravado igual ao instante do redirect (regressão de P4).
11. N criações paralelas com cota restante 1 → exatamente um 201.
12. Duas rotações paralelas do mesmo refresh token → exatamente uma sucede.
13. Mesmo alias em paralelo → um 201 e um 409.
14. `HEAD /{code}` → 302 sem clique; `Cache-Control: no-store` presente.
15. A aplicação sobe com Redis e Kafka indisponíveis.
16. 429 com `Retry-After`; fail-open do limitador com Redis fora.
17. 503 de exaustão com um `ShortCodeGenerator` falso.
18. Senha com acentos/emojis acima de 72 bytes → 400, nunca 500.

Os cenários de concorrência (11–13) usam `@SpringBootTest` com threads reais e `TransactionTemplate`: o `@DataJpaTest`
é transacional com rollback por teste e não prova concorrência.

Gate: JaCoCo ≥ 80% de linhas fora de `config`, aplicado no `verify`, medido num único `jacoco.exec` (todos os testes no
Surefire, ou `merge` de Surefire e Failsafe). `./mvnw verify` só exige Docker.

**Nota.** No Testcontainers 2.x mudaram as coordenadas dos módulos e os pacotes das classes
(`org.testcontainers.postgresql.PostgreSQLContainer`, `org.testcontainers.kafka.KafkaContainer`). O Redis via
`GenericContainer` precisa de `@ServiceConnection(name = "redis")`. Tudo isso é validado no spike da F0.

### D15 — Configuração, ambientes e infraestrutura local

> **Ajustada pelo ADR-0002** (D9: Dockerfile por módulo, jar `exec`, CI) **e pelo ADR-0003** (D5, D11: sai o
> `APP_JWT_SECRET`; entram as variáveis e o serviço do `auth-server` no compose).

**Decisão.**
- Propriedades tipadas em `@ConfigurationProperties("app")` com `@Validated` (fail fast no startup): `baseUrl`,
  `links.*`, `cache.*`, `analytics.*`, `rateLimit.*`, `jwt.*`.
- Segredos só por variável de ambiente (`APP_JWT_SECRET`, `SPRING_DATASOURCE_PASSWORD`…). Um `.env.example` é
  versionado; o `.env` fica no `.gitignore`. Não há `spring.config.import` de `.env`.
- Perfis:
  - `local`: `spring.docker.compose.enabled=true` (em `application.yml` o padrão é `false`), Swagger ligado, segredo
    JWT de desenvolvimento explícito no `application-local.yml`. O `spring-boot-docker-compose` conecta PostgreSQL e
    Redis automaticamente, **mas não o Kafka**: `spring.kafka.bootstrap-servers=localhost:9092` fica explícito no
    perfil.
  - `test`: Testcontainers.
  - padrão (produção): sem Swagger, logs JSON, todos os segredos obrigatórios.
- `compose.yaml`:
  - imagens fixadas: `postgres:18.6-alpine`, `redis:8.8.4-alpine`, `apache/kafka:4.4.0`;
  - Kafka KRaft com `KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092`;
  - Redis sem persistência (`--save "" --appendonly no`);
  - portas presas a `127.0.0.1`, healthchecks e volumes para PostgreSQL e Kafka.
- `Dockerfile` multi-stage (build com Maven, runtime `eclipse-temurin:25-jre`, usuário não root, jar em camadas,
  `HEALTHCHECK` em `/livez`).
- `.mise.toml` no projeto fixa `java = "corretto-25"`.
- CI no GitHub Actions: `./mvnw -B verify`.

---

## 4. Fora do escopo (v1)

- Interface web e páginas HTML de erro.
- Domínios personalizados, QR code, criação em lote, edição de destino (D5).
- Verificação de e-mail, recuperação de senha por e-mail, login social, papéis de administrador.
- **Exclusão de conta** (direito do titular, LGPD art. 18) — prioridade para a v2.
- Domínios internacionais sem Punycode (suporte UTS-46).
- Safe Browsing / blocklist de destinos (há ponto de extensão `TargetUrlPolicy`).
- Geolocalização, detecção de bots e crawlers de pré-visualização.
- Job de retenção/expurgo de cliques e particionamento de `click_events` (necessário acima de ~12 meses de dados).
- Multi-região, L1 cache local, exportação de tracing via OTLP.

## 5. Riscos e questões em aberto

| # | Risco / questão | Mitigação ou decisão pendente |
|---|---|---|
| R1 | Kafka pode ser peso operacional demais para a escala | Interface `ClickRecorder` permite trocar pela opção D8-D sem tocar no redirect |
| R2 | Java 25 ainda não instalado no ambiente | Instalação via mise na F0; fallback para Java 21 exigiria tratar *pinning* e trocar `InetAddress.ofLiteral` por parser próprio |
| R3 | APIs novas ou recém-alteradas: `NimbusJwtEncoder.withSecretKey`, Testcontainers 2.x, módulos de tracing do Boot 4 | Spike na F0, antes de qualquer código de negócio |
| R4 | Perda de cliques na fila em memória num crash | Aceito e documentado; métricas de descarte |
| R5 | HS256 com segredo compartilhado | Resolvido pelo ADR-0003: RS256 com JWKS |
| R6 | Janela fixa permite rajadas 2× | Aceito; Bucket4j como evolução |
| R7 | 409 no cadastro permite enumerar e-mails | Aceito, mitigado por rate limit |
| R8 | Renovações concorrentes do mesmo refresh token derrubam a sessão | Aceito; clientes serializam o refresh. Deixa de existir na v1 do ADR-0003, sem refresh token |

## 6. Rastreabilidade: problemas do original → decisões

| Problema | Resolvido por |
|---|---|
| P1 cache não invalidado no delete | D7: tombstone incondicional after-commit + `SET NX` no preenchimento, fechando também a corrida leitor × remoção |
| P2 Kafka no caminho do redirect (e conexões retidas no pool) | D8: fila em memória + thread dedicada; redirect sem transação aberta durante I/O externo |
| P3 rollback do status expirado | D5: `EXPIRED` derivado, sem escrita na leitura |
| P4 horário do clique errado | D6/D8: `occurred_at` vem do evento, sem `@CreationTimestamp` |
| P5 token inválido → 500 | D9: Resource Server + entry point em ProblemDetail |
| P6 Swagger bloqueado | D9: permitido no perfil `local` |
| P7 handler com classe errada | D9/D12: handlers do Spring Security + advice global |
| P8 fuso fixo no JWT | D9: `Instant` + `Clock` compartilhado entre emissão e validação |
| P9 PATCH público com cast | D9: rota autenticada, `UserId` explícito, sem cast de principal |
| P10 `create-drop` + Redis persistente | D6: Flyway + `validate`; D7: Redis sem persistência, TTL ≤ 10 min, chave versionada |
| P11 código liberado para reuso após delete | D5: soft delete, código reservado para sempre |
| P12 cota check-then-act | D5: advisory lock por usuário em READ COMMITTED |
| P13 cache gravado antes do commit | D7: nenhuma escrita de cache dentro de transação; invalidação só after-commit |
| S1 sem validação | D10 |
| S2 enumeração no login | D9: 401 genérico |
| S3 troca de senha fraca | D9: senha atual + revogação de refresh tokens; D11: rate limit por usuário |
| S4 status HTTP incoerentes | D9/D12 |
| Q1 hash truncado + check-then-act | D3: aleatório + `UNIQUE` + `ON CONFLICT` |
| Q2 testes insuficientes | D14 |

## 7. Plano de implementação

Cada fase termina com `./mvnw verify` verde. Testes de degradação entram na fase do componente que eles protegem.

| Fase | Entrega |
|---|---|
| F0 | Java 25 via mise; esqueleto (`pom.xml`, wrapper, `compose.yaml`, perfis, `AppProperties`, `Clock`, UUIDv7), ProblemDetail base, logs estruturados, springdoc, teste Modulith, CI. **Spike:** Testcontainers (PostgreSQL, Redis, Kafka, Toxiproxy) subindo com `@ServiceConnection`, round-trip JWT encode → decode, conexão Kafka pelo compose |
| F1 | `identity` + `V1__identity.sql`: cadastro, login, Resource Server, entry point/handler em ProblemDetail, refresh com rotação e detecção de reuso, logout, troca de senha, purge |
| F2 | `link` + `V2__links.sql`: domínio (`ShortCode`, `TargetUrl`, `HostClassifier`, `Alias`), geração com `ON CONFLICT`, cota com advisory lock, CRUD, listagem paginada, evento `LinkDeleted` |
| F3 | `redirect`: resolução, cache-aside com `SET NX`, tombstones, invalidação after-commit, `RedisGuard`, 302/404/410, métricas `shortener.redirect`, testes com Redis parado e pendurado |
| F4 | `analytics` + `V3__click_events.sql`: `ClickRecorder` com fila, `ClickSender`, consumer idempotente, DLT, estatísticas com `topReferrers`, métricas de cliques, health de publicação, testes com Kafka fora (no startup e em execução) |
| F5 | Rate limit (filtro + Lua) e cenários de abuso |
| F6 | Porta de gestão e health groups finais, `Dockerfile`, README, revisão da documentação OpenAPI |

## 8. Registro de revisão

### Revisão 1 — 2026-10-31 (revisor de arquitetura independente)

Veredito: **aprovado com ressalvas** (2 bloqueantes, 9 importantes, 9 sugestões). Todas as ressalvas foram
incorporadas; as versões citadas pelo revisor foram reconferidas no Maven Central e no Docker Hub.

| ID | Severidade | Tema | Decisão | Onde |
|---|---|---|---|---|
| R-01 | Bloqueante | `shared` com subpacotes falharia o `verify()`; invalidador no módulo errado; Modulith não é só de teste nem gerenciado pelo Boot | Aceito | D1, D2, §2.3 |
| R-02 | Bloqueante | Limite de 72 bytes do bcrypt com senha não ASCII → 500 | Aceito | D9, D14 (#18) |
| R-03 | Importante | Corrida leitor × invalidação; cache negativo não absorve varredura | Aceito | D3, D7, §2.1, §2.2 |
| R-04 | Importante | Reuso de refresh com rollback da revogação; sem validade absoluta; sem expurgo | Aceito | D6, D9 |
| R-05 | Importante | Algoritmo implícito no encoder; `iss`/`aud` não validados; `Clock` divergente no validador | Aceito | D9 |
| R-06 | Importante | HEAD bloqueado; 401/403/429 sem corpo; actuator exposto a qualquer conta | Aceito | D4, D9, D11, D13 |
| R-07 | Importante | Bypass de validação: IDN, IPv4 não canônico, IPv6, ponto final, expansão do encoding | Aceito com ajuste: `InetAddress.ofLiteral` (Java 25) em vez de Guava | D10 |
| R-08 | Importante | Fail-open do Redis custa latência (fila do Lettuce, timeouts, pool) | Aceito | D7, D11, D14 (#5) |
| R-09 | Importante | DLT em lote, nome da DLT, `max.block.ms`, fase de parada, admin timeout | Aceito com ajuste: destino da DLT explícito (`link-clicks-dlt`) em vez de depender do padrão da versão | D8 |
| R-10 | Importante | Testes de degradação no fim; migração única incoerente com as fases | Aceito | D6, §7 |
| R-11 | Importante | Concorrência sem teste; armadilhas de slice; JaCoCo não gerenciado | Aceito | D14 |
| R-12 | Sugestão | `FOR UPDATE` em `users` cruza fronteira e conflita com FK; isolamento | Aceito: advisory lock | D3, D5, §2.2 |
| R-13 | Sugestão | Boot 4.1.2; compose não conecta Kafka; compose ativo em todo perfil; tags fixas | Aceito | D1, D15 |
| R-14 | Sugestão | Java 25 em vez de 21 | Aceito (requer instalação, R2) | D1, D15, §7 |
| R-15 | Sugestão | Sem health de Kafka no Boot; dependências de Prometheus/tracing; cardinalidade | Aceito com ajuste: indicador próprio baseado no `ClickSender` | D1, D13 |
| R-16 | Sugestão | `password_hash` curto; instantes do `Clock`; UUIDv7; collation; checks; índice parcial; estimativa | Aceito com ajuste: colunas sem `default now()` (mais estrito que o sugerido) | D6, §1.5 |
| R-17 | Sugestão | Rastreabilidade incompleta | Aceito: P11–P13 e efeito do P2 | §1.1, §6 |
| R-18 | Sugestão | Durabilidade do D8 enganosa; consumo em lote e cache 404 superdimensionados; `referrer_host` sem uso | Aceito com ajuste: `topReferrers` mantém a coluna útil | D8, D12, §4 |
| R-19 | Sugestão | Proxy headers, IPv6 /64, ordem do filtro, limites por e-mail e na troca de senha | Aceito | D11 |
| R-20 | Sugestão | Direcionadores, decisores, regra de evolução | Aceito | cabeçalho |

### Notas de implementação — F0 (2026-10-31)

Nenhuma decisão foi alterada. Achados do spike (R3), para orientar as próximas fases:

| Tema | Resultado |
|---|---|
| Testcontainers 2.0.5 | `@ServiceConnection` funciona com `org.testcontainers.postgresql.PostgreSQLContainer`, `org.testcontainers.kafka.KafkaContainer` e `GenericContainer` do Redis com `name = "redis"` |
| Toxiproxy 2.13.0 | o toxic `timeout` faz o Lettuce falhar com `RedisCommandTimeoutException` dentro do timeout configurado: base pronta para os testes de degradação da F3 |
| JWT | ida e volta com `NimbusJwtEncoder.withSecretKey(..).algorithm(HS256)`; o Spring Security 7.1.2 já tem `JwtAudienceValidator` e `JwtTypeValidator`, usados no lugar de validadores genéricos. `Jwt.getIssuer()` exige URL; como o `iss` é uma string simples, ler com `getClaimAsString("iss")` |
| Hikari | `connection-timeout` é numérico em milissegundos (`2000`), não aceita `2s` |
| Slices web | controllers aninhados em classes de teste são excluídos do scan pelo `TestTypeExcludeFilter`: entram por `@Import` |
| Compose | PostgreSQL publicado em `127.0.0.1:5433` (a 5432 já estava ocupada no ambiente de desenvolvimento); Kafka alcançável do host pelo listener anunciado `localhost:9092` |
| Logs | JSON ECS no perfil padrão; texto nos perfis `local` e `test`. O campo `traceId` será conferido no primeiro log emitido durante uma requisição (F1) |

### Notas de implementação — F1 (2026-11-01)

Dois ajustes pequenos em D9, ambos mais restritivos que o texto original, e achados da implementação:

| Tema | Resultado |
|---|---|
| Logout (ajuste) | Revoga a **sessão** (família) do token apresentado, não só o token. Com rotação, a família tem um único token ativo, então o efeito normal é o mesmo; a diferença é que um logout com token já rotacionado também encerra a sessão |
| E-mail ASCII (ajuste) | O cadastro aceita só e-mail ASCII. Fora do ASCII, `toLowerCase(Locale.ROOT)` do Java e `lower()` do PostgreSQL divergem, e o check `ck_users_email_lower` recusaria a linha com 500. Coerente com o Punycode exigido em D10 |
| Propriedades | Seções de um único módulo ficam nele: `app.refresh-token.*` é `identity.application.RefreshTokenProperties`, em vez de crescer o `AppProperties` de `shared`. As seções `links.*`, `cache.*` e `analytics.*` seguirão o mesmo padrão |
| Login | `DaoAuthenticationProvider` criado dentro do `AuthService`, não como bean: um `AuthenticationManager` publicado viraria o pai do gerenciador da cadeia JWT. `InternalAuthenticationServiceException` (ex.: banco fora) vira 500, não um 401 enganoso |
| Mensagens | Bean Validation em português com `spring.web.locale=pt_BR` e `spring.web.locale-resolver=fixed` |
| `sub` | Validado como id numérico positivo (`CurrentUser.isUserId`), para que nenhum token aceito quebre o `Long.parseLong` do controller |
| Metadata RFC 9728 | O Spring Security 7 publica `/.well-known/oauth-protected-resource` e o anuncia no `WWW-Authenticate`, sem opção de desligar. O padrão declara `tls_client_certificate_bound_access_tokens=true`; corrigido para `false` |
| `kid` do JWT | `NimbusJwtEncoder.withSecretKey` grava o thumbprint SHA-256 da chave. Não abre ataque novo: a assinatura HS256 já permite testar candidatos offline, e o segredo aleatório de 32+ bytes torna isso inviável |
| `JdbcClient` e tempo | Instantes vão ao driver como `OffsetDateTime` em UTC (`shared.time.SqlTimestamps`) |
| Troca de senha | O bcrypt roda fora da transação (`TransactionTemplate` só na escrita), para não reter conexão do pool |
| Nomes | `EmailAddress` em vez de `Email` (conflito com `@Email`); escrita de ProblemDetail fora do MVC em `shared.error.ProblemResponseWriter`; `@MaxUtf8Bytes` em `shared.validation` |
| `traceId` | Conferido: `traceId` e `spanId` no JSON ECS do perfil padrão e no texto dos perfis `local`/`test`, nos logs emitidos durante requisições |
| Compose | `spring-boot-docker-compose` não entra no jar empacotado: o perfil `local` sobe só por `./mvnw spring-boot:run` |
| Testes | Cenários 8, 9, 12 e 18 do D14 cobertos; 90 testes, 97% de linhas fora de config |
| Fail fast | sem `APP_JWT_SECRET`, o startup falha no binding de `app.jwt.secret` |

### Notas de implementação — F2 (2026-11-01)

Um ajuste pequeno em D10 e achados da implementação:

| Tema | Resultado |
|---|---|
| Página máxima (ajuste) | `page` vai de 0 a 10 000. O offset do JPA é `int`: sem limite, um `page` alto estourava e virava 500 (RNF04) |
| Validação da criação | `url`, `alias` e `expiresAt` são regras do domínio (`TargetUrl`, `Alias`, validade calculada com o `Clock`). O serviço aplica as três e devolve todas as violações juntas no mesmo 400 `VALIDATION_FAILED` com `errors[]` do Bean Validation, via `shared.error.InvalidFieldsException`, que a F4 reaproveita para `from`/`to`/`tz` |
| Transação da criação | A validação roda antes de abrir a transação (`TransactionTemplate`): requisição inválida não ocupa conexão do pool. O `INSERT … RETURNING id` e os instantes truncados em microssegundos garantem que a resposta mostra exatamente o que foi gravado, como no cadastro da F1 |
| `save()` indisponível | `LinkRepository` estende `Repository`, não `JpaRepository`: a criação só existe pelo `INSERT … ON CONFLICT` (D3) |
| Conta inexistente | Token válido de conta que não existe mais faz a FK de `owner_id` falhar (SQLState 23503); a criação devolve 401 `INVALID_TOKEN`, como a troca de senha (`CurrentUser.accountNotFound()`) |
| Códigos reservados | O gerador também descarta palavras reservadas; só é possível com `code-length` 6 (`health`, `static`…) |
| `java.net.URI` | Aceita `0177.0.0.1` como IPv4 e `2130706433` como nome: a regra (b) do D10 cobre os dois. Recusa host com `_` (`meu_host.com`), que fica recusado (limitação aceita). `InetAddress.ofLiteral` já devolve IPv4 para `::ffff:a.b.c.d` |
| Fronteira do módulo | A F2 expõe só `LinkDeleted`. `LinkLookup`/`LinkSnapshot` entram na F3, junto com o primeiro consumidor; `LinkStatus` segue em `link.domain` até lá. `PageResponse` ficou em `shared.web` |
| Métricas | `shortener.links.created`, `shortener.code.collisions` e `shortener.code.exhausted`, sem tags |
| Logs | Criação e remoção logam usuário, código e host; a URL completa (query strings podem carregar segredos) não aparece |
| Testes | Cenários 11, 13 e 17 do D14 cobertos. O 11 foi conferido por mutação: sem o advisory lock, 6 das 8 criações paralelas passaram da cota. Um `ShortCodeGenerator` programável `@Primary` força colisões sem criar um segundo contexto (`support.TestDoublesConfiguration`); 267 testes, 98% de linhas fora de config |
| Migração | `V2__links.sql` aplicada sobre o banco local que já tinha os dados da F1 |

### Notas de implementação — F3 (2026-11-01)

Um ajuste no cenário 5 do D14 e achados da implementação:

| Tema | Resultado |
|---|---|
| Redis pendurado (ajuste) | O cenário 5 usa o toxic `latency` (respostas atrasadas em 2 s), não o `timeout`. O `timeout` descarta bytes, o que um TCP real nunca faz, e dessincronizaria as respostas na conexão compartilhada do Lettuce: um comando poderia receber a resposta de outro. Um Redis travado de verdade atrasa as respostas, e o Lettuce descarta as dos comandos que já expiraram |
| API do módulo `link` | `LinkLookup.findByCode` devolve `LinkSnapshot` (inclusive removido e expirado). `LinkStatus` subiu para o pacote raiz, porque faz parte do snapshot. A rota usa `LinkLookup.CODE_PATTERN`, sem repetir a regex fora do módulo dono |
| Códigos de erro | 404 `LINK_NOT_FOUND`, 410 `LINK_EXPIRED` ou `LINK_DELETED`, em `application/problem+json` mesmo com `Accept: text/html` |
| Camadas do cache | A porta `LinkCache` fica na aplicação; chave, JSON e `RedisGuard` ficam em `redirect.infra.RedisLinkCache`. Entrada ilegível conta como erro e cai no banco |
| Preenchimento | Só depois de um miss de verdade: se a leitura falhou, uma escrita custaria outro timeout; com o disjuntor aberto, nem seria tentada. Link a menos de 1 ms de expirar não é cacheado (o Redis recusa TTL zero) |
| `shortener.cache.errors{op}` | `read` (falha ou entrada ilegível), `write` e `invalidate`. Em `invalidate`, disjuntor aberto também conta: a entrada ativa só sai pelo TTL |
| `RedisGuard` | `app.redis-guard.failure-threshold` (5) e `open-duration` (5 s; 1 s no perfil de teste, para que as classes seguintes não esperem). Com o disjuntor fechado, não há lock no caminho quente |
| Lettuce | `REJECT_COMMANDS` via `LettuceClientOptionsBuilderCustomizer`, que preserva os timeouts configurados pelo Boot. Conferido por mutação: sem ele, o primeiro comando depois de uma queda espera o timeout inteiro (113 ms medidos). O atraso padrão de reconexão bastou: nos testes, o cache voltou em cerca de 1 s |
| Startup sem Redis | O Lettuce conecta sob demanda, então a aplicação sobe sem Redis. O cenário 15 completo, com o Kafka, fica para a F4 |
| Testes | No contexto de teste, a aplicação fala com o Redis sempre pelo Toxiproxy (`DataRedisConnectionDetails` apontando para o proxy). Cenários 2, 3, 4, 5 e a parte de redirecionamento do 14 cobertos; 303 testes, 98% de linhas fora de config |
| Aplicação real | Com o container do Redis parado, o redirecionamento continuou em 302, de 3 a 5 ms por requisição; a remoção devolveu 204 e contou a falha de invalidação; o disjuntor fechou sozinho quando o Redis voltou |
| Métricas | Histograma de `shortener.redirect` publicado no Prometheus (`percentiles-histogram`), para acompanhar o p99 do RNF01 |

### Notas de implementação — F4 (2026-11-01)

Três ajustes em D8 e D10, todos para evitar erro ou 500, e achados da implementação:

| Tema | Resultado |
|---|---|
| Leitura do evento (ajuste) | O consumidor recebe o valor como texto e o lê com o `JsonMapper` da aplicação, com tipo fixo e sem cabeçalho de tipo, em vez do `ErrorHandlingDeserializer`. A garantia é a mesma: mensagem ilegível, de versão desconhecida ou incompleta vai para a DLT sem retry. Com o deserializer, o recoverer receberia bytes crus e precisaria de outro serializer só para a DLT |
| `request.timeout.ms` (ajuste) | 10 s. O Kafka exige `delivery.timeout.ms >= linger.ms + request.timeout.ms`; com o padrão de 30 s, os valores do D8 (30 s e 20 ms) não fecham e o produtor nem seria criado |
| Fuso das estatísticas (ajuste) | Só nomes IANA que o PostgreSQL também conhece, porque o agrupamento por dia roda lá. Offsets como `+03:00` são recusados: o PostgreSQL os lê no sentido POSIX, com o sinal invertido. A família legada `SystemV/*`, que o Java aceita e o PostgreSQL não, também: um teste que percorre todos os fusos do Java a encontrou, e cada um deles teria virado 500 |
| Período | Sem `to`, termina hoje no fuso pedido; sem `from`, cobre 30 dias. Datas fora de 1970-01-01 a 9999-12-30 dão 400 (evita estouro de data e o 500 que viria dele) |
| Parâmetro de tipo errado | `page=abc` ou `from=ontem` agora dão 400 `VALIDATION_FAILED` com o campo em `errors[]`, como as demais validações (antes era um 400 genérico) |
| API do módulo `link` | `LinkLookup.findOwned` atende a posse nas estatísticas: link de outro usuário ou removido dá 404 |
| Clique | Só `GET` que redireciona gera clique; `HEAD` não. O `eventId` (UUIDv7) e o `occurredAt` saem do redirecionamento; do Referer, só o host |
| Fase do `ClickSender` | `WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE` está descontinuada no Boot 4.1; a fase é `WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1024`, abaixo do servidor web e acima do produtor do Kafka (`Integer.MIN_VALUE`) |
| Falhas de publicação | Contadas em `shortener.clicks.publish.failed`; só a primeira de uma sequência vai para o log, e a volta é registrada uma vez |
| `kafkaPublish` | Fica DOWN depois de uma entrega que falhou (o que leva o `delivery.timeout.ms`, 30 s) e volta a UP no próximo sucesso. Derruba o status raiz de `/actuator/health`, não a readiness |
| Risco: PostgreSQL fora | Falhas transitórias do banco têm 2 retries (cerca de 2 s) e depois vão para a DLT. Numa queda longa do banco, os cliques param na DLT: não se perdem, mas a v1 não tem ferramenta de reprocessamento |
| Testes | Cenários 1, 6, 7, 10, 14 e 15 do D14 cobertos. Kafka pendurado com `docker pause`; o cenário 15 sobe uma segunda instância com Redis e Kafka em portas fechadas. 340 testes, 98% de linhas fora de config |
| Aplicação real | Com o container do Kafka parado, o redirecionamento seguiu em 3 a 4 ms; depois de 30 s, `kafkaPublish` ficou DOWN com a readiness em 200. Os 5 cliques da queda se perderam (queda maior que o `delivery.timeout.ms`, perda aceita em D8); o clique seguinte à volta foi publicado. Numa pausa curta, nos testes, nenhum clique se perdeu |
| Migração | `V3__click_events.sql` aplicada sobre o banco local com os dados da F1 e da F2 |

### Notas de implementação — F5 (2026-11-01)

Dois ajustes em D11 e achados da implementação:

| Tema | Resultado |
|---|---|
| Limite por e-mail (ajuste) | Fica no `AuthService`, não no filtro: o filtro teria de ler e reconstruir o corpo da requisição, e o serviço já recebe o e-mail normalizado. Conta toda tentativa, inclusive com a senha certa, antes do bcrypt. O 429 sai pelo handler global no mesmo formato do filtro (`RateLimitExceededException`) |
| Chave no Redis (ajuste) | `rl:{alvo}:{hash}:{janela}`, com os 128 primeiros bits do SHA-256 do sujeito no lugar dele. E-mail e IP são dados pessoais e não precisam ficar legíveis no Redis (RNF08) |
| Filtro | Não é bean: o `SecurityConfig` o cria e o põe logo depois do `BearerTokenAuthenticationFilter`, o que dispensa o `FilterRegistrationBean`. Requisição com token inválido recebe 401 antes de contar. Regra por usuário sem usuário autenticado não conta: a autorização devolve 401 logo adiante |
| Rotas | Comparação literal de método e caminho: o firewall do Spring Security já recusa `//`, `;` e barras codificadas. O limite do redirecionamento vale para todo `GET`/`HEAD` de um segmento, o que inclui varreduras com caminhos quaisquer, exceto `/livez` e `/readyz` |
| Janela | Calculada com o `Clock` da aplicação; o TTL da chave é o fim da janela mais 1 s. Requisições recusadas também incrementam o contador da janela |
| 429 | Código `RATE_LIMITED`, `Retry-After` em segundos arredondado para cima e nunca zero |
| Métricas | `shortener.ratelimit.rejected{rule}` e `shortener.ratelimit.failopen{rule}` |
| Testes | Todos os testes saem de 127.0.0.1 (o `ApiClient` fixa o IPv4), então o perfil de teste usa limites altíssimos; o teste do rate limit preenche o contador da janela até o limite configurado e confere o 429 em cada rota. Cenário 16 do D14 coberto; 377 testes, 98% de linhas fora de config |
| Aplicação real | Com os limites de produção: a 6ª tentativa no mesmo e-mail e a 11ª no mesmo IP receberam 429; exatamente 600 redirecionamentos passaram e o 601º recebeu 429, com a readiness em 200. Com o Redis parado, o login seguiu normalmente e as liberações foram contadas |

### Notas de implementação — F6 (2026-11-01)

Nenhuma decisão foi alterada. Achados da implementação:

| Tema | Resultado |
|---|---|
| Imagem | `eclipse-temurin:25.0.5_8-jdk-noble` no build e `25.0.5_8-jre-noble` no runtime, fixadas como as do compose. Build com o Maven Wrapper e cache do BuildKit; os testes não rodam na imagem (precisam de Docker e rodam no CI). Camadas do Boot (`-Djarmode=tools extract --layers`): o código da aplicação ocupa 233 kB na última camada, e as dependências, 103 MB numa camada que só muda com o `pom.xml`. Usuário `app` (uid 10001), `MaxRAMPercentage=75` e `ExitOnOutOfMemoryError`. Cerca de 198 MB comprimida |
| `HEALTHCHECK` | A JRE da imagem não traz `curl` nem `wget`: o `curl` é instalado só para o healthcheck em `/livez` |
| Compose | Serviço `app` atrás do profile `app` do compose, para que o perfil `local` do Spring Boot continue subindo só a infraestrutura. O Kafka ganhou o listener `DOCKER://kafka:29092`: até a F5, ele só anunciava `localhost:9092`, inalcançável de outro container. A porta de gestão é publicada só em 127.0.0.1 |
| Conferido em containers | A aplicação fica `healthy` em segundos e roda como uid 10001; o fluxo completo funciona, inclusive cliques pelo listener interno do Kafka. Com o Redis parado por 35 s, o container continuou `healthy` e a readiness em 200, enquanto o health completo foi a 503: é o motivo de o `HEALTHCHECK` usar `/livez`. Sem `APP_JWT_SECRET`, a aplicação recusa subir |
| Health e info | Os grupos de liveness e readiness ficaram como estavam. `/actuator/info` passou a mostrar versão e horário do build (`build-info`) e a JVM |
| OpenAPI | Título, versão do build, esquema Bearer nos controllers protegidos, tags e códigos de erro por operação. O schema `Problem` espelha o JSON real, com `code` e `errors[]` na raiz, e não dentro de `properties` como no schema padrão do `ProblemDetail`. Corrigido também o que o springdoc gerava sozinho: a criação de link aparecia como 200 (é 201), o redirecionamento como 200 (é 302, agora com `Location` e `Cache-Control`), os erros repetiam o schema de sucesso, e os records aninhados viravam schemas chamados `Day` e `Referrer` |
| CI | Job `docker build` depois do `verify` |
| Testes | 377 testes, 98% de linhas fora de config |
