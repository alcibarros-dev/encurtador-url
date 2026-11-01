# Encurtador de URLs

Encurtador de links com contas de usuário, cache de resolução no Redis e estatísticas de cliques assíncronas via
Kafka. É a reescrita completa do projeto
[Antony-Reis/encurtadorUrl](https://github.com/Antony-Reis/encurtadorUrl), feita a partir de um ADR revisado antes da
primeira linha de código: [docs/adr/0001-arquitetura-encurtador-url.md](docs/adr/0001-arquitetura-encurtador-url.md).

**O que ele faz**

- Cadastro, login, refresh com rotação (reuso do refresh token derruba a sessão), logout e troca de senha.
- Links com código aleatório de 7 caracteres ou alias próprio, validade de 1 minuto a 365 dias e cota de 100 links
  ativos por usuário. O destino é validado como entrada hostil: só `http`/`https`, sem credenciais, sem IP privado
  em nenhuma forma, sem loop para o próprio encurtador.
- `GET /{code}` responde 302 a partir do cache e continua respondendo com o Redis ou o Kafka fora do ar.
- Estatísticas por link: total, série diária num fuso escolhido e principais referers. Sem IP nem user-agent: do
  Referer, só o host.
- Rate limit por IP, usuário ou e-mail, com 429 e `Retry-After`.

## Stack

Java 25 · Spring Boot 4.1 (MVC com virtual threads) · Spring Security 7 (Resource Server com JWT) · PostgreSQL 18 ·
Redis 8 · Kafka 4.4 (KRaft) · Flyway · Spring Modulith · springdoc-openapi · Testcontainers · JaCoCo.

## Arquitetura

Monólito modular: um pacote por funcionalidade, com as fronteiras verificadas pelo Spring Modulith a cada build.

```text
br.com.encurtador
├── identity    contas, credenciais e sessões
├── link        criação, listagem e remoção de links; validação do destino
├── redirect    GET /{code}: cache-aside no Redis, tombstones, invalidação após o commit
├── analytics   fila em memória → Kafka → consumidor idempotente → estatísticas
└── shared      erros RFC 9457, segurança, disjuntor do Redis, rate limit, tempo
```

Dependências permitidas: `redirect → link, analytics` · `analytics → link` · `link`, `identity → shared`.

Caminho do redirecionamento:

```text
GET /{code} → rate limit (Redis) → cache (Redis) ─ miss/erro/disjuntor aberto ─→ PostgreSQL
                                        │
                                        └→ 302 · Location · Cache-Control: private, no-store
                                           └→ clique na fila em memória (não bloqueia) → thread própria → Kafka
```

O Redis fica atrás de um disjuntor: com timeouts de 100 ms e 5 falhas seguidas, ele é ignorado por 5 s, e o
redirecionamento segue pelo banco. O redirect nunca espera o Kafka. Os motivos de cada escolha, as alternativas
descartadas e as notas de implementação de cada fase estão no [ADR](docs/adr/0001-arquitetura-encurtador-url.md).

## Como rodar

Pré-requisitos: Docker e Java 25. Com o [mise](https://mise.jdx.dev), `mise install` na raiz instala a versão
fixada em `.mise.toml`.

### Desenvolvimento

O perfil `local` sobe o PostgreSQL, o Redis e o Kafka do `compose.yaml`, liga o Swagger UI e usa um segredo JWT de
desenvolvimento:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Actuator: http://localhost:8081/actuator/health

O perfil `local` só funciona pelo Maven: o suporte a Docker Compose não entra no jar empacotado.

### Tudo em containers

O serviço `app` do compose constrói a imagem e sobe junto com a infraestrutura:

```bash
APP_JWT_SECRET=$(openssl rand -base64 48) docker compose --profile app up --build
```

### pgAdmin, redis-commander e Kafdrop

Os três sobem junto com a infraestrutura do compose, nos dois modos acima, e só aceitam conexões desta máquina:

| Ferramenta | Endereço | O que mostra |
|---|---|---|
| pgAdmin | http://localhost:16543 | O PostgreSQL: contas, links e cliques. Abre sem login, com o servidor `encurtador-url` já conectado |
| redis-commander | http://localhost:8181 | O Redis: o cache de resolução (`link:v1:*`) e os contadores de rate limit (`rl:*`) |
| Kafdrop | http://localhost:9000 | O Kafka: os tópicos `link-clicks` e `link-clicks-dlt`, suas partições e mensagens, e o atraso do consumer group `click-consumer` |

- O servidor do pgAdmin vem de [docker/pgadmin/servers.json](docker/pgadmin/servers.json), lido só na primeira subida.
  Depois de mudar o arquivo, remova o container e o volume do pgAdmin para ele importar de novo (o nome do volume
  leva o nome da pasta do projeto):

  ```bash
  docker compose rm -sf pgadmin && docker volume rm encurtador-url_pgadmin_data
  ```

- No Redis, a chave `link:v1:{code}` só aparece depois do primeiro acesso ao link curto, e nada sobrevive a uma
  reinicialização do container: ele roda sem persistência.
- No Kafka, a aplicação cria os tópicos ao subir, e cada acesso a um link curto vira uma mensagem em `link-clicks`.

### Só a imagem

```bash
docker build -t encurtador-url .
```

A imagem roda como usuário sem privilégios, tem `HEALTHCHECK` em `/livez` e recebe a configuração por variáveis de
ambiente:

| Variável | Exemplo |
|---|---|
| `APP_JWT_SECRET` | Base64 de pelo menos 32 bytes aleatórios (`openssl rand -base64 48`); sem ela, a aplicação não sobe |
| `APP_BASE_URL` | `https://encurta.dor` (prefixo do `shortUrl`; o encurtador recusa destinos para esse host) |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | `jdbc:postgresql://db:5432/encurtador` |
| `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` | `redis`, `6379` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `kafka:29092` |

O [.env.example](.env.example) traz os valores para rodar fora do perfil `local`.

## Usando a API

```bash
curl -s -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"fulano@exemplo.com","password":"senha-segura-1"}'
```

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"fulano@exemplo.com","password":"senha-segura-1"}' | jq -r .accessToken)
```

```bash
curl -s -X POST localhost:8080/api/v1/links -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"url":"https://exemplo.com/artigo","alias":"meu-artigo"}'
```

```bash
curl -si localhost:8080/meu-artigo
```

```bash
curl -s "localhost:8080/api/v1/links/meu-artigo/stats?tz=America/Sao_Paulo" -H "Authorization: Bearer $TOKEN"
```

| Método | Caminho | Auth | Sucesso | Erros principais |
|---|---|---|---|---|
| POST | `/api/v1/auth/register` | — | 201 | 400, 409 `EMAIL_TAKEN`, 429 |
| POST | `/api/v1/auth/login` | — | 200 | 400, 401 `INVALID_CREDENTIALS`, 429 |
| POST | `/api/v1/auth/refresh` | refresh token | 200 | 401 `INVALID_REFRESH_TOKEN`, 429 |
| POST | `/api/v1/auth/logout` | refresh token | 204 | — |
| PUT | `/api/v1/users/me/password` | Bearer | 204 | 400, 401, 422 `WRONG_CURRENT_PASSWORD`, 429 |
| POST | `/api/v1/links` | Bearer | 201 | 400, 409 `ALIAS_TAKEN` / `LINK_QUOTA_EXCEEDED`, 429, 503 |
| GET | `/api/v1/links?page&size` | Bearer | 200 | 400 |
| GET | `/api/v1/links/{code}` | Bearer | 200 | 404 |
| DELETE | `/api/v1/links/{code}` | Bearer | 204 | 404 |
| GET | `/api/v1/links/{code}/stats?from&to&tz` | Bearer | 200 | 400, 404 |
| GET, HEAD | `/{code}` | — | 302 | 404, 410 `LINK_EXPIRED` / `LINK_DELETED`, 429 |

Todo erro é `application/problem+json` (RFC 9457) com um `code` estável e, em `VALIDATION_FAILED`, a lista
`errors[]` com todos os campos inválidos de uma vez. Link de outro usuário responde 404, igual a um inexistente.

Limites padrão (`app.rate-limit.*`):

| Alvo | Chave | Limite |
|---|---|---|
| Login | IP / e-mail | 10 por minuto / 5 em 15 minutos |
| Cadastro | IP | 10 por hora |
| Refresh | IP | 30 por minuto |
| Troca de senha | usuário | 5 em 15 minutos |
| Criação de link | usuário | 30 por minuto |
| Redirecionamento | IP | 600 por minuto |

## Testes

```bash
./mvnw verify
```

Só exige Docker: o Testcontainers sobe PostgreSQL, Redis, Kafka e Toxiproxy reais. O build roda os testes unitários,
os de integração, a verificação de módulos do Spring Modulith e o gate de cobertura (JaCoCo, mínimo de 80% de linhas
fora de configuração; hoje em torno de 98%).

Além dos fluxos da API, os testes cobrem os cenários de falha do ADR:

- o redirect segue em 302 com o Redis parado e, com ele pendurado, mantém o p99 abaixo de 50 ms;
- o redirect não espera o Kafka pendurado, e a aplicação sobe sem Redis e sem Kafka;
- removido com o cache quente, o link responde 410 já no acesso seguinte;
- cota, alias e rotação de refresh token disputados em paralelo têm um só vencedor;
- mensagem envenenada vai para a DLT, e evento duplicado vira um único registro;
- o rate limit responde 429 e libera as requisições com o Redis fora.

## Operação

| Porta | Conteúdo |
|---|---|
| 8080 | API, redirecionamento, `/livez` e `/readyz` |
| 8081 | Actuator completo (`health`, `info`, `metrics`, `prometheus`); só para a rede interna |

- **Liveness** (`/livez`): só o estado da aplicação. É o que o `HEALTHCHECK` da imagem usa.
- **Readiness** (`/readyz`): aplicação e PostgreSQL. Redis e Kafka ficam de fora de propósito: sem eles, a aplicação
  degrada, mas continua servindo.
- **Health completo** (`:8081/actuator/health`): inclui `redis` e `kafkaPublish` (a última publicação de cliques
  deu certo?).
- **Métricas**: `shortener.redirect` (timer por resultado e por cache, com histograma para o p99),
  `shortener.links.created`, `shortener.code.collisions`, `shortener.clicks.*`, `shortener.cache.errors`,
  `shortener.redis.circuit`, `shortener.ratelimit.rejected` e `shortener.ratelimit.failopen`.
- **Logs**: JSON ECS com `traceId` e `spanId` fora do perfil `local`. Nunca registram senha, token nem URL de destino
  inteira (só o host).

Atrás de um proxy reverso, o rate limit precisa do IP real do cliente: use `server.forward-headers-strategy=native`
com `server.tomcat.remoteip.internal-proxies` restrito aos proxies confiáveis.

## Limitações conhecidas

- Analytics é best-effort: cliques na fila em memória se perdem num crash, e uma queda do Kafka maior que 30 s
  (`delivery.timeout.ms`) perde os cliques do período.
- Com o PostgreSQL fora por mais de uns 2 s, os cliques consumidos vão para a DLT; não há ferramenta de
  reprocessamento na v1.
- Códigos curtos não são controle de acesso: o rate limit encarece a varredura, mas não a impede.
- Crawlers de pré-visualização (WhatsApp, Slack) contam como cliques.
- Domínios internacionais precisam vir em Punycode (`xn--…`).
- A janela fixa do rate limit permite rajadas de até 2× na virada. No Docker Desktop, todos os clientes podem
  aparecer com o mesmo IP.
- Fora do escopo da v1: interface web, domínios personalizados, edição de destino, verificação de e-mail e exclusão
  de conta (a próxima prioridade, pela LGPD).
