# Encurtador de URLs

Encurtador de links com contas de usuário, cache de resolução no Redis e estatísticas de cliques assíncronas via
Kafka, feita a partir de ADRs revisados antes da primeira linha de código:

- [ADR-0001](docs/adr/0001-arquitetura-encurtador-url.md): arquitetura e decisões da reescrita (implementado);
- [ADR-0002](docs/adr/0002-modulos-maven.md): divisão em módulos Maven (implementado);
- [ADR-0003](docs/adr/0003-servidor-de-autorizacao.md): servidor de autorização com o Spring Authorization Server
  (implementado);
- [ADR-0004](docs/adr/0004-frontend-angular.md): painel web em Angular, atrás de um BFF (implementado).

**O que ele faz**

- Painel web (Angular, atrás de um BFF): cadastro, login, logout e troca de senha pelas páginas do `auth-server`,
  gestão dos links e estatísticas do usuário. Nenhum token chega ao navegador: ele só tem o cookie de sessão do BFF.
- Contas num servidor de autorização próprio (`auth-server`, OAuth 2.1 e OpenID Connect): cadastro, login, logout e
  troca de senha em páginas dele, e login da CLI pelo fluxo de dispositivo. O app nunca vê senha: valida os access
  tokens pelas chaves públicas do emissor e continua validando com ele fora do ar.
- Links com código aleatório de 7 caracteres ou alias próprio, validade de 1 minuto a 365 dias e cota de 100 links
  ativos por usuário. O destino é validado como entrada hostil: só `http`/`https`, sem credenciais, sem IP privado
  em nenhuma forma, sem loop para o próprio encurtador.
- `GET /{code}` responde 302 a partir do cache e continua respondendo com o Redis ou o Kafka fora do ar.
- Estatísticas por link e de todos os links do usuário: total, série diária num fuso escolhido, principais links e
  referers. Sem IP nem user-agent: do Referer, só o host.
- Rate limit por IP, usuário, e-mail ou cliente OAuth, com 429 e `Retry-After`.

## Stack

Java 25 · Spring Boot 4.1 (MVC com virtual threads) · Spring Security 7 (Resource Server com JWT RS256 e Spring
Authorization Server) · PostgreSQL 18 · Redis 8 · Kafka 4.4 (KRaft) · Flyway · Spring Modulith · springdoc-openapi ·
Testcontainers · JaCoCo. No painel: Angular 22 (standalone, sem Zone.js, signals e Signal Forms) · Angular Material 22 ·
Chart.js · Vitest · Playwright com axe-core.

## Arquitetura

Monólito modular dividido em módulos Maven ([ADR-0002](docs/adr/0002-modulos-maven.md)): plataforma técnica de um
lado, negócio do outro, e uma aplicação que junta tudo. Ao lado dele, o servidor de autorização, uma aplicação
separada no mesmo repositório ([ADR-0003](docs/adr/0003-servidor-de-autorizacao.md)).

```text
encurtador-url/
├── platform/
│   ├── shared/         erros RFC 9457, tempo, UUIDv7, disjuntor do Redis
│   ├── ratelimit/      janela fixa no Redis (Lua) e o filtro que aplica as regras de cada módulo
│   ├── security-api/   contratos de segurança, sem framework: @CurrentUser, UserIdResolver, PublicRoutes
│   └── security/       validação do token pelo JWKS, cadeia de filtros, respostas 401, 403 e 503
├── modules/
│   ├── account/        contas do app: o sub do token vira o id local, com criação no primeiro acesso
│   ├── link/           criação, listagem e remoção de links; validação do destino
│   ├── analytics/      fila em memória → Kafka → consumidor idempotente → estatísticas
│   └── redirect/       GET /{code}: cache-aside no Redis, tombstones, invalidação após o commit
├── app/                classe principal, configuração, migrações e testes de integração
├── auth-server/        servidor de autorização: login, cadastro, troca de senha, tokens RS256, JWKS
├── web/                painel em Angular, empacotado como jar (META-INF/resources); e2e/ com o Playwright
├── bff/                BFF do painel: entrega a SPA, login OIDC, sessão e repasse de /api/** ao app
├── coverage/           sem código: o gate de cobertura de todos os módulos
├── scripts/            teste ponta a ponta da CLI sobre o compose
└── docs/               ADRs e roteiros de operação
```

Dependências entre módulos:

| Módulo | Depende de |
|---|---|
| `shared`, `security-api` | — |
| `ratelimit` | `shared` |
| `security` | `security-api`, `shared` |
| `account` | `security-api` |
| `link` | `security-api`, `ratelimit`, `shared` |
| `analytics` | `link`, `security-api`, `ratelimit`, `shared` |
| `redirect` | `link`, `analytics`, `security-api`, `ratelimit`, `shared` |
| `app` | todos os de plataforma e de negócio |
| `auth-server` | `shared`, `ratelimit` |
| `web` | — (só pacotes do npm) |
| `bff` | `web`, `shared`, `ratelimit` |
| `coverage` | todos os de Java |

O Maven barra na compilação um import de módulo não declarado. O Spring Modulith continua verificando a cada build o
que o Maven não vê: os subpacotes de cada módulo são internos, e só o pacote raiz é API. O `security` não conhece os
módulos de negócio nem quem emite o token: cada módulo declara as próprias rotas públicas e regras de rate limit, e o
`account` traduz o `sub` do token no id do usuário. Trocar o `auth-server` por outro provedor OIDC, como o Keycloak, é
configuração e migração de dados, sem código no app (ADR-0003, D13).

Login e validação do token:

```text
navegador ou CLI ──login──→ auth-server (8090) ──access token JWT RS256, 15 min──→ cliente
cliente ──Authorization: Bearer──→ app (8080): assinatura pela chave pública do JWKS (em cache),
                                   iss, aud, exp e typ → @CurrentUser → account: sub → id local
```

O painel não é cliente direto do app: o navegador só fala com o BFF, na mesma origem, e o BFF guarda os tokens na
sessão dele ([ADR-0004](docs/adr/0004-frontend-angular.md)).

```text
navegador ── cookie de sessão e de CSRF ──→ bff (8070) ├─ a SPA (arquivos estáticos)
   │                                                     ├─ /oauth2/**, /login/**, /bff/**: login, sessão e logout
   │                                                     └─ /api/v1/links/**, /api/v1/stats ── Bearer ──→ app (8080)
   └─ login, cadastro, troca de senha e logout ──→ auth-server (8090)
```

O BFF é um cliente confidencial (`encurtador-web`): código de autorização com PKCE e refresh token sem rotação por 30
dias, guardado só na sessão em memória, que expira depois de 2 horas sem uso. A SPA manda a página inteira para o
login (`/oauth2/authorization/encurtador?returnTo=…`) e devolve o token de CSRF do cookie `XSRF-TOKEN` no cabeçalho
`X-XSRF-TOKEN`. A `Content-Security-Policy` só aceita scripts da própria origem.

O app busca o JWKS sob demanda e o guarda em cache: um `kid` novo dispara uma busca, limitada a duas por 30 s, e, com
o `auth-server` fora, as chaves já obtidas continuam valendo por até um dia. Sem nenhuma chave utilizável, a requisição
autenticada responde 503 `AUTH_UNAVAILABLE`.

Caminho do redirecionamento:

```text
GET /{code} → rate limit (Redis) → cache (Redis) ─ miss/erro/disjuntor aberto ─→ PostgreSQL
                                        │
                                        └→ 302 · Location · Cache-Control: private, no-store
                                           └→ clique na fila em memória (não bloqueia) → thread própria → Kafka
```

O Redis fica atrás de um disjuntor: com timeouts de 100 ms e 5 falhas seguidas, ele é ignorado por 5 s, e o
redirecionamento segue pelo banco. O redirect nunca espera o Kafka. Os motivos de cada escolha, as alternativas
descartadas e as notas de implementação de cada fase estão nos ADRs: o
[0001](docs/adr/0001-arquitetura-encurtador-url.md) para a arquitetura, o [0002](docs/adr/0002-modulos-maven.md) para
a divisão em módulos e o [0003](docs/adr/0003-servidor-de-autorizacao.md) para o servidor de autorização.

## Como rodar

Pré-requisitos: Docker e Java 25. Com o [mise](https://mise.jdx.dev), `mise install` na raiz instala a versão
fixada em `.mise.toml`.

### Desenvolvimento

O perfil `local` sobe o PostgreSQL, o Redis e o Kafka do `compose.yaml` e liga o Swagger UI. São três aplicações,
cada uma num terminal: o servidor de autorização, que emite os tokens, o app, que os valida, e o BFF, que serve o
painel.

```bash
./mvnw -pl auth-server -am spring-boot:run
```

```bash
./mvnw spring-boot:run
```

```bash
./mvnw -pl bff -am spring-boot:run
```

- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html (o botão Authorize faz login no `auth-server` pelo fluxo de
  código com PKCE, com o cliente `encurtador-dev`)
- Actuator: http://localhost:8081/actuator/health
- `auth-server`: http://localhost:8090 (cadastro em `/register`); actuator em http://localhost:8091
- Painel: http://localhost:8070; actuator do BFF em http://localhost:8071

Para mexer no Angular com recarga automática, o `ng serve` sobe o painel em http://localhost:4200, repassando `/api`,
`/bff`, `/oauth2` e `/login` ao BFF (que precisa estar no ar). O Node é o 26, fixado no `.mise.toml`:

```bash
npm --prefix web ci
```

```bash
npm --prefix web start
```

Os comandos rodam na raiz, onde fica o `compose.yaml`: ali, o `spring-boot:run` sobe o `app`, o primeiro do reactor,
e as outras aplicações sobem com `-pl <módulo> -am`. O build do painel instala o próprio Node e roda o lint, os testes
e o build do Angular. O perfil Maven `local`, ativo por padrão, liga o perfil `local` do
Spring Boot; outro perfil do Spring entra com `-Dspring-boot.run.profiles=<perfil>`. O perfil `local` só funciona pelo
Maven ou pela IDE: o suporte a Docker Compose não entra no jar empacotado. Nele, o `auth-server` assina com uma chave
de desenvolvimento versionada, que nunca deve ser usada fora desse perfil.

No IntelliJ, as Run Configurations vêm com o repositório, em `.idea/runConfigurations/`:

| Configuração | O que faz |
|---|---|
| `encurtador-url (local)` | Roda `spring-boot:run -pl app -am` pelo Maven na raiz, com o perfil Maven `local`: compila só o `app` e os módulos de que ele depende |
| `encurtador-url (local, debug)` | Sobe `EncurtadorApplication` direto pela IDE, no módulo `encurtador-url-app`, com o perfil `local`, para depurar com breakpoints |
| `auth-server (local)` | Roda `spring-boot:run -pl auth-server -am` pelo Maven na raiz, com o perfil Maven `local` |
| `auth-server (local, debug)` | Sobe `AuthServerApplication` direto pela IDE, com o perfil `local` |
| `bff (local)` | Roda `spring-boot:run -pl bff -am` pelo Maven na raiz, com o perfil Maven `local` |
| `bff (local, debug)` | Sobe `BffApplication` direto pela IDE, com o perfil `local` |
| `stack (local)` | Sobe as três configurações Maven acima juntas |

Todas usam o JDK do projeto, que precisa ser o 25.

### Tudo em containers

Os serviços `auth-server`, `app` e `bff` do compose constroem as imagens e sobem junto com a infraestrutura. O painel
fica em http://localhost:8070. Em segundo plano, liberando o terminal:

```bash
docker compose --profile app up -d --build
```

Ou em primeiro plano, com os logs de todos os serviços no terminal (Ctrl+C para tudo):

```bash
docker compose --profile app up --build
```

O Swagger UI fica em http://localhost:8080/swagger-ui.html: o compose liga a documentação, que a imagem deixa
desligada por padrão. Nele, cole um access token obtido pela CLI (veja [Usando a API](#usando-a-api)).

Sem chave de assinatura, o `auth-server` do compose gera uma a cada subida, e os tokens emitidos param de valer quando
o container reinicia. Para manter os tokens entre reinicializações, gere uma chave em `.secrets/`, pasta que o Git e a
imagem ignoram e que o compose monta no container:

```bash
mkdir -p .secrets && openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .secrets/auth-signing-key.pem && chmod 644 .secrets/auth-signing-key.pem
```

O `chmod 644` deixa o usuário sem privilégios do container ler o arquivo; fora da máquina de desenvolvimento, a chave
entra por um secret do orquestrador. O segredo do cliente `encurtador-web`, do painel, tem um valor de
desenvolvimento no compose; para usar outro, defina `AUTH_WEB_CLIENT_SECRET` num `.env` na raiz, que o compose lê
sozinho e o Git ignora.

Para conferir a stack inteira de ponta a ponta: pela CLI (cadastro, fluxo de dispositivo, link, redirecionamento e
estatística) e pelo painel no Playwright (cadastro, links, estatísticas, troca de senha e saída, com o axe em cada
página):

```bash
scripts/ponta-a-ponta.sh
```

Todos os comandos do Playwright rodam na raiz, como os outros: o `--prefix` aponta para o projeto `web/e2e`. Na
primeira vez, instale as dependências e o Chromium:

```bash
npm --prefix web/e2e ci
```

```bash
npm --prefix web/e2e run browsers
```

Depois, com a stack no ar:

```bash
npm --prefix web/e2e test
```

Para ver o navegador executando os testes: com a janela do Chromium aberta e uma pausa entre as ações (`SLOW_MO`, em
milissegundos; sem ela, o teste completo leva uns 6 segundos):

```bash
SLOW_MO=1000 npm --prefix web/e2e run test:headed
```

Ou na interface do Playwright, que mostra cada passo, a página em cada momento e permite rodar um teste de cada vez:

```bash
npm --prefix web/e2e run test:ui
```

Quando um teste falha, o relatório abre com o trace da execução:

```bash
npm --prefix web/e2e run report
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

```bash
docker build --build-arg MODULE=auth-server --build-arg HTTP_PORT=8090 --build-arg MANAGEMENT_PORT=8091 -t encurtador-auth-server .
```

```bash
docker build --build-arg MODULE=bff --build-arg HTTP_PORT=8070 --build-arg MANAGEMENT_PORT=8071 -t encurtador-bff .
```

O Dockerfile serve para qualquer aplicação do repositório: `--build-arg MODULE=<módulo>` escolhe qual (o padrão é
`app`), e `HTTP_PORT` e `MANAGEMENT_PORT` ajustam as portas do `EXPOSE` e do `HEALTHCHECK`.

As imagens rodam como usuário sem privilégios, têm `HEALTHCHECK` em `/livez` e recebem a configuração por variáveis de
ambiente. Todas usam `SPRING_DATA_REDIS_HOST` e `SPRING_DATA_REDIS_PORT`; o app e o `auth-server`, também
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD`. Além delas:

| Aplicação | Variável | Exemplo |
|---|---|---|
| app | `APP_BASE_URL` | `https://encurta.dor` (prefixo do `shortUrl`; o encurtador recusa destinos para esse host) |
| app | `APP_JWT_ISSUER` | `https://auth.encurta.dor`: o `iss` exigido, a URL pública do `auth-server`, cujo host também não pode ser destino de link |
| app | `APP_JWT_JWK_SET_URI` | `http://auth-server:8090/oauth2/jwks`: de onde vêm as chaves públicas, pela rede interna |
| app | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `kafka:29092` |
| auth-server | `AUTH_ISSUER` | `https://auth.encurta.dor` (vai no `iss` dos tokens) |
| auth-server | `AUTH_SIGNING_KEY_ID`, `AUTH_SIGNING_KEY_FILE` | `auth-2`, `file:/run/secrets/auth-signing-key.pem` (PEM PKCS#8; sem ele, a chave é gerada na subida) |
| auth-server | `AUTH_WEB_CLIENT_SECRET` | segredo do cliente `encurtador-web` (`openssl rand -base64 32`) |
| auth-server | `AUTH_WEB_REDIRECT_URIS`, `AUTH_WEB_POST_LOGOUT_REDIRECT_URIS` | `https://painel.encurta.dor/login/oauth2/code/encurtador`, `https://painel.encurta.dor/` |
| auth-server | `AUTH_PANEL_ORIGINS`, `AUTH_PANEL_DEFAULT_URL` | `https://painel.encurta.dor`, `https://painel.encurta.dor/` |
| bff | `APP_BASE_URL` | `https://painel.encurta.dor` (endereço público do painel) |
| bff | `BFF_API_URL` | `http://app:8080` (o app, pela rede interna) |
| bff | `BFF_AUTH_PUBLIC_URL`, `BFF_AUTH_INTERNAL_URL` | `https://auth.encurta.dor` (o emissor, para onde o navegador vai), `http://auth-server:8090` |
| bff | `BFF_CLIENT_SECRET` | o mesmo `AUTH_WEB_CLIENT_SECRET` do `auth-server` |
| bff | `BFF_REGISTRATION_URL`, `BFF_PASSWORD_CHANGE_URL` | páginas de cadastro e de troca de senha do provedor; por padrão, as do `auth-server` |

Fora do HTTPS (como no compose), o BFF precisa de `SERVER_SERVLET_SESSION_COOKIE_NAME=WEBSESSION`,
`SERVER_SERVLET_SESSION_COOKIE_SECURE=false`, `BFF_COOKIES_CSRF_NAME=XSRF-TOKEN` e `BFF_COOKIES_SECURE=false`: o padrão
são cookies `__Host-` com `Secure`.

O [.env.example](.env.example) traz os valores para rodar fora do perfil `local`.

## Usando a API

O login é no `auth-server`. Num terminal, o cliente `encurtador-cli` usa o fluxo de dispositivo (RFC 8628): pede um
código, o usuário o confirma no navegador, e o terminal recebe o token.

Os comandos abaixo rodam no mesmo terminal, um depois do outro, e usam o [jq](https://jqlang.org). Primeiro, a CLI
pede um código e guarda a resposta em `DEVICE` (o comando não mostra nada):

```bash
DEVICE=$(curl -s -X POST localhost:8090/oauth2/device_authorization -d client_id=encurtador-cli -d scope=email)
```

Mostra o endereço de confirmação, já com o código:

```bash
echo "$DEVICE" | jq -r .verification_uri_complete
```

Abra esse endereço no navegador, entre (ou crie a conta em `/register`) e confirme o acesso na tela de
consentimento. Depois, troque o código pelo access token, que vale 15 minutos:

```bash
TOKEN=$(curl -s -X POST localhost:8090/oauth2/token -d client_id=encurtador-cli -d grant_type=urn:ietf:params:oauth:grant-type:device_code -d device_code="$(echo "$DEVICE" | jq -r .device_code)" | jq -r .access_token)
```

```bash
echo "$TOKEN"
```

O token é um texto longo que começa com `eyJ`. Se aparecer `null`, o código ainda não foi confirmado no navegador ou
já expirou: peça outro com o primeiro comando.

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

```bash
curl -s "localhost:8080/api/v1/stats?tz=America/Sao_Paulo" -H "Authorization: Bearer $TOKEN"
```

```bash
curl -s -X DELETE "localhost:8080/api/v1/links/meu-artigo" -H "Authorization: Bearer $TOKEN"
```

A CLI não tem refresh token: depois de 15 minutos, ela confirma um código novo.

## O que cada módulo expõe

**bff** (`bff/`, porta 8070): o painel

| Caminho | O que é |
|---|---|
| `/`, `/links`, `/links/{code}`, `/estatisticas`, `/conta` | A SPA (`index.html`, sem cache); outro endereço do navegador recebe o `index.html` com 404 |
| `/oauth2/authorization/encurtador?returnTo=` | Início do login; a volta só aceita as rotas logadas da SPA |
| `GET /bff/session` | E-mail do usuário (do `id_token`), ou 401 |
| `GET /bff/register`, `GET /bff/password` | Leva às páginas de cadastro e de troca de senha do provedor, com a volta para o painel |
| `POST /bff/logout` | Revoga o refresh token, encerra a sessão e devolve a URL de logout do provedor |
| `/api/v1/links/**`, `/api/v1/stats` | Repasse ao app com o access token, renovado pelo refresh token; outra rota de `/api/**`, 404 |

Sem sessão, `/api/**` e `/bff/**` respondem 401 em problem+json, nunca um redirecionamento; app fora ou lento, 503
`UPSTREAM_UNAVAILABLE`; refresh token recusado (por exemplo, depois de uma troca de senha), 401 `SESSION_EXPIRED`.

**auth-server** (`auth-server/`, porta 8090): credenciais, login e emissão de tokens

| Caminho | O que é |
|---|---|
| `/login`, `/logout` | Login e logout do navegador (sessão `AUTHSESSION`, CSRF ligado) |
| `/register` | Cadastro: e-mail e senha de 8 a 64 caracteres (até 72 bytes), com login automático |
| `/account/password` | Troca de senha: exige a atual e encerra as autorizações ativas |
| `/activate` | Confirmação do código do fluxo de dispositivo, sempre com consentimento |
| `/oauth2/authorize`, `/oauth2/token`, `/oauth2/device_authorization`, `/connect/logout`, `/userinfo` | Protocolo OAuth 2.1 e OpenID Connect; erros no formato do OAuth (`error`, `error_description`) |
| `/oauth2/jwks`, `/.well-known/openid-configuration` | Chaves públicas e descoberta |

Clientes registrados: `encurtador-cli` (público, fluxo de dispositivo), `encurtador-web` (o BFF do painel,
confidencial, código com PKCE e refresh token) e, só no perfil `local`, `encurtador-dev` (público, código com PKCE,
para o Swagger UI e o Postman). O access token leva `sub` (identificador estável da credencial), `aud`
`encurtador-api`, `email` e `scope`.

**account** (`modules/account/`): sem rotas. Traduz o `sub` do token no id da conta, criando a conta no primeiro
acesso; o app guarda só o `subject` e as datas.

**link** (`modules/link/`): criação, listagem, consulta e remoção de links

| Método | Caminho | Auth | Sucesso | Erros principais |
|---|---|---|---|---|
| POST | `/api/v1/links` | Bearer | 201 | 400, 409 `ALIAS_TAKEN` / `LINK_QUOTA_EXCEEDED`, 429, 503 |
| GET | `/api/v1/links?page&size` | Bearer | 200 | 400 |
| GET | `/api/v1/links/{code}` | Bearer | 200 | 404 |
| DELETE | `/api/v1/links/{code}` | Bearer | 204 | 404 |

**analytics** (`modules/analytics/`): cliques nos links e suas estatísticas

Rotas HTTP:

| Método | Caminho | Auth | Sucesso | Erros principais |
|---|---|---|---|---|
| GET | `/api/v1/links/{code}/stats?from&to&tz` | Bearer | 200 | 400, 404 |
| GET | `/api/v1/stats?from&to&tz` | Bearer | 200 | 400, 429 |

Consumidores Kafka:

| Listener | Tópico | Grupo | Mensagem | Erros |
|---|---|---|---|---|
| `ClickConsumer` (`@KafkaListener` `click-consumer`) | `link-clicks` (3 partições, chave = id do link) | `click-consumer` | `LinkClicked` em JSON (`v`, `eventId`, `linkId`, `occurredAt`, `referrerHost`), gravado em `click_events` | 2 novas tentativas com 1 s de intervalo, depois `link-clicks-dlt`; JSON ilegível, versão desconhecida e link inexistente vão direto para a DLT |

- Quem publica em `link-clicks` é o `ClickSender`, também do `analytics`, a partir da fila em memória que o
  `redirect` alimenta a cada `GET /{code}`. O redirect nunca espera o Kafka.
- O consumo é idempotente: o mesmo `eventId` entregue duas vezes gera um único registro.
- Ninguém consome a `link-clicks-dlt`: ela guarda as mensagens recusadas para inspeção (no Kafdrop, por exemplo),
  e não há reprocessamento na v1.
- Os tópicos são criados pela aplicação ao subir (`app.analytics.topic`, `app.analytics.dead-letter-topic`).

**redirect** (`modules/redirect/`): o redirecionamento

| Método | Caminho | Auth | Sucesso | Erros principais |
|---|---|---|---|---|
| GET, HEAD | `/{code}` | — | 302 | 404, 410 `LINK_EXPIRED` / `LINK_DELETED`, 429 |

No app, todo erro é `application/problem+json` (RFC 9457) com um `code` estável e, em `VALIDATION_FAILED`, a lista
`errors[]` com todos os campos inválidos de uma vez. Link de outro usuário responde 404, igual a um inexistente. Token
ausente, inválido, expirado, de outro emissor ou de outra audiência responde 401; sem chave pública utilizável, 503
`AUTH_UNAVAILABLE` com `Retry-After`.

Limites padrão (`app.rate-limit.*`):

| Aplicação | Alvo | Chave | Limite |
|---|---|---|---|
| auth-server | Login | IP / e-mail | 10 por minuto / 5 em 15 minutos |
| auth-server | Cadastro | IP | 10 por hora |
| auth-server | Troca de senha | usuário | 5 em 15 minutos |
| auth-server | `/oauth2/token` | IP / `client_id` dos clientes confidenciais | 30 por minuto / 600 por minuto |
| auth-server | Pedido e confirmação de código do dispositivo | IP | 10 por minuto cada |
| app | Criação de link | usuário | 30 por minuto |
| app | Redirecionamento | IP | 600 por minuto |
| app | Estatísticas do usuário | usuário | 30 por minuto |
| bff | Início do login e do cadastro | IP | 20 por minuto |

## Testes

```bash
./mvnw verify
```

Só exige Docker: o Testcontainers sobe PostgreSQL, Redis, Kafka e Toxiproxy reais. O build roda os testes unitários
de cada módulo, os de integração (no `app`, no `auth-server` e no `bff`, porque sobem a aplicação inteira), o lint, os
testes e o build do painel, a verificação de módulos do Spring Modulith e o gate de cobertura (JaCoCo, mínimo de 80% de linhas fora de configuração; hoje em torno de 97%). O gate e
o relatório ficam no módulo `coverage`, que junta a cobertura de todos: `coverage/target/site/jacoco-aggregate/`. O
painel tem gate próprio, de 80% de linhas no Vitest (`web/coverage/`).

Para testar um módulo sozinho, com os módulos de que ele depende:

```bash
./mvnw -pl modules/link -am test
```

Além dos fluxos da API, os testes cobrem os cenários de falha do ADR:

- o redirect segue em 302 com o Redis parado e, com ele pendurado, mantém o p99 abaixo de 50 ms;
- o redirect não espera o Kafka pendurado, e a aplicação sobe sem Redis e sem Kafka;
- removido com o cache quente, o link responde 410 já no acesso seguinte;
- cota, alias e o primeiro acesso de um usuário novo disputados em paralelo têm um só vencedor;
- com o `auth-server` fora, tokens de chaves já conhecidas continuam valendo; sem nenhuma chave, 503;
- no `auth-server`: fluxos de código com PKCE e de dispositivo, cadastro, troca de senha, limites, rotação de chave e
  claims emitidos;
- mensagem envenenada vai para a DLT, e evento duplicado vira um único registro;
- o rate limit responde 429 e libera as requisições com o Redis fora.

Os testes do app não precisam do `auth-server`: um servidor HTTP de teste publica um JWKS com uma chave RSA de teste,
e os tokens são assinados com ela, pelo mesmo caminho de produção. Os do BFF trocam o app e o `auth-server` por
servidores HTTP falsos (repasse, renovação e revogação de token, timeouts). A stack inteira é conferida sobre o compose
pelo `scripts/ponta-a-ponta.sh` e pelo Playwright em `web/e2e`, que o CI roda num job próprio.

## Operação

| Porta | Conteúdo |
|---|---|
| 8080 | app: API, redirecionamento, `/livez` e `/readyz` |
| 8081 | app: actuator completo (`health`, `info`, `metrics`, `prometheus`); só para a rede interna |
| 8090 | auth-server: páginas, protocolo OAuth, `/livez` e `/readyz` |
| 8091 | auth-server: actuator completo; só para a rede interna |
| 8070 | bff: o painel, `/livez` e `/readyz` |
| 8071 | bff: actuator completo; só para a rede interna |

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

**Rotação da chave de assinatura** (ADR-0003, D5): suba o `auth-server` com a chave nova em `AUTH_SIGNING_KEY_ID` e
`AUTH_SIGNING_KEY_FILE` e a anterior em `APP_AUTH_PREVIOUS_SIGNING_KEY_ID` e `APP_AUTH_PREVIOUS_SIGNING_KEY_LOCATION`.
O JWKS publica as duas, os tokens novos saem com o `kid` novo, e o app aprende a chave nova sozinho. Depois de 15
minutos, o tempo de vida de um access token, retire a anterior.

**Migração das contas** do login antigo do app para o `auth-server`: veja o
[roteiro](docs/runbooks/migracao-auth-server.md).

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
- O `auth-server` guarda a sessão do navegador em memória: uma instância só. Ele também guarda em claro, até o
  expurgo diário, os tokens que emitiu.
- A CLI não tem refresh token: confirma um código novo a cada 15 minutos.
- O BFF também guarda a sessão em memória: uma instância, e um reinício pede login de novo (silencioso enquanto a
  sessão do `auth-server` valer). O refresh token do painel não rotaciona.
- Fora do escopo da v1: domínios personalizados, edição de destino, verificação de e-mail e exclusão de conta (a
  próxima prioridade, pela LGPD).
