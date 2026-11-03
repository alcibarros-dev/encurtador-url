# ADR-0003 — Servidor de autorização com Spring Authorization Server

| Campo | Valor |
|---|---|
| Status | **Aceito** (2026-11-02), após a revisão 1 (§8) · implementação pendente (A0 a A4) |
| Data | 2026-11-02 |
| Escopo | Tirar login, cadastro, senha e emissão de tokens do app; criar o `auth-server` (OAuth 2 / OpenID Connect) |
| Substitui | ADR-0001 D9 (autenticação: emissão própria de JWT, login e refresh na API). Ajusta ADR-0001 D1, D6, D10, D11, D12, D13, D14, D15, RF02, R5 e R8, e ADR-0002 D1, D2, D4, D5, D7, D8 e D9 |
| Depende de | ADR-0002 (estrutura em módulos, `security-api`, `UserIdResolver`, auto-configurações de plataforma) |
| Decisores | Dono do projeto (aprovação) · autor da proposta · revisor de arquitetura independente (§8) |

**Direcionadores.** O provedor de identidade tem de ser trocável (por exemplo, pelo Keycloak) sem mudar o código do
app; a senha do usuário não deve passar pelo app; seguir padrões abertos (OAuth 2, OpenID Connect, JWT, JWKS) em vez
de um protocolo próprio; manter o app funcionando quando o servidor de autorização estiver fora.

---

## 1. Contexto

Hoje o app faz tudo: recebe e-mail e senha em `POST /api/v1/auth/login`, guarda o hash, emite access tokens JWT
assinados com um segredo HMAC (`APP_JWT_SECRET`), gerencia refresh tokens e valida os tokens que ele mesmo emitiu
(ADR-0001 D9). Consequências:

- quem obtém o `APP_JWT_SECRET` consegue fabricar tokens válidos para qualquer usuário;
- trocar por um provedor como o Keycloak muda o contrato de login dos clientes: com um provedor OpenID Connect, o
  cliente não manda a senha ao app, porque o fluxo com senha direto (*resource owner password credentials*) não deve
  ser usado segundo a RFC 9700 (§2.4, boas práticas de segurança do OAuth 2.0, 2025) e foi retirado do rascunho do
  OAuth 2.1;
- o ADR-0002 já isolou o `security` do provedor, mas o provedor continua dentro do app (pacote `identity`).

O Spring Authorization Server (SAS) implementa OAuth 2 e OpenID Connect. Desde o Spring Security 7.0 ele faz parte do
Spring Security (`org.springframework.security:spring-security-oauth2-authorization-server`, versão 7.1.2 na BOM que o
projeto já usa), com o starter `spring-boot-starter-security-oauth2-authorization-server` gerenciado pelo Spring Boot
4.1.2. Nenhuma versão nova entra no projeto.

## 2. Decisões

### D1 — Papéis e vocabulário

| Papel (OAuth) | Quem é | O que faz com o token |
|---|---|---|
| Cliente | CLI, Postman, Swagger UI | Obtém o access token e o envia; para ele, o token é opaco |
| Servidor de autorização | `auth-server` (este ADR) | Autentica o usuário e assina os tokens |
| Servidor de recurso | o app (rotas `/api/v1/**` e `/{code}`) | Valida o access token em toda requisição |

Neste ADR, *app* é sempre o servidor de recurso. O cliente só valida o `id_token` do OpenID Connect, uma vez, no login,
se precisar saber quem entrou; o access token é validado pelo app.

### D2 — `auth-server`: aplicação separada no mesmo repositório

```text
auth-server/                       encurtador-url-auth-server → ratelimit, shared
├── pom.xml                        starter do SAS, thymeleaf, jdbc, flyway, data-redis, actuator
├── scripts/importar-usuarios.sql  importação única das credenciais de hoje (D8)
└── src/main/
    ├── java/br/com/encurtador/auth/
    │   ├── AuthServerApplication.java
    │   ├── config/        endpoints OAuth2/OIDC, cadeia do formulário, clientes, sessão, CORS
    │   ├── credential/    credenciais (bcrypt), política de senha, cadastro, troca de senha, limites de login
    │   ├── token/         chave de assinatura RSA (JWKSource), claims do access token, expurgo
    │   └── web/           páginas de login, cadastro, troca de senha e confirmação de dispositivo
    └── resources/
        ├── templates/     Thymeleaf
        └── db/migration/  schema auth
```

- **Portas 8090** (protocolo e páginas) e **8091** (actuator), publicadas só em `127.0.0.1` no compose. A 9000 é do
  Kafdrop. Imagem própria, construída pelo mesmo Dockerfile (`--build-arg MODULE=auth-server`, ADR-0002 D9), com o
  jar `exec` (ADR-0002 D7).
- Depende só de `platform/shared` e `platform/ratelimit`, cujos beans chegam por auto-configuração (ADR-0002 D11),
  sem escanear `br.com.encurtador`. Não depende do `security`, do `security-api` nem de módulos de negócio; o app não
  depende dele.
- Endpoints do SAS: `/oauth2/authorize`, `/oauth2/token`, `/oauth2/device_authorization`, `/oauth2/device_verification`,
  `/oauth2/jwks`, `/oauth2/revoke`, `/oauth2/introspect`, `/.well-known/openid-configuration`,
  `/.well-known/oauth-authorization-server`, `/userinfo`, `/connect/logout`. Páginas próprias: `/login`, `/register`,
  `/account/password` e a confirmação de dispositivo.
- Cookie de sessão com nome próprio (`AUTHSESSION`): cookies de `localhost` não se separam por porta.
- Observabilidade igual à do app (ADR-0001 D13): actuator na porta de gestão, `/livez` e `/readyz`, logs JSON ECS com
  `traceId`. Os logs nunca registram senha, token, código de autorização, `device_code` nem `user_code`.

Estrutura do repositório depois deste ADR:

```text
encurtador-url/
├── platform/    shared/  ratelimit/  security-api/  security/
├── modules/     account/  link/  analytics/  redirect/
├── app/         servidor de recurso, portas 8080 e 8081 (o pacote identity sai)
├── auth-server/ servidor de autorização, portas 8090 e 8091
└── coverage/
```

### D3 — Clientes registrados

| Cliente | Tipo | Fluxo | Uso |
|---|---|---|---|
| `encurtador-cli` | público | `device_code`, com consentimento obrigatório | Terminal e curl |
| `encurtador-dev` | público | `authorization_code` com PKCE | Postman (`https://oauth.pstmn.io/v1/callback`) e Swagger UI (`http://localhost:8080/swagger-ui/oauth2-redirect.html`). Registrado só no perfil `local` |

- PKCE obrigatório em todo `authorization_code`.
- Clientes registrados por propriedades do Spring Boot (`spring.security.oauth2.authorizationserver.client.*`), sem
  tabela de clientes: a lista é fixa e versionada. O tempo de vida do access token é configurado explicitamente em 15
  minutos (o padrão do SAS é 5).
- O SAS só autentica cliente público quando a requisição traz `code_verifier`. Para o fluxo de dispositivo, o
  `auth-server` registra um conversor e um provider próprios em `clientAuthentication(...)`, que aceitam o `client_id`
  do `encurtador-cli` em `/oauth2/device_authorization` e na troca do `device_code`.
- **Sem refresh token na v1.** Nenhum cliente recebe o grant `refresh_token`. O SAS não revoga a sessão no reuso de um
  refresh token já trocado, não tem validade absoluta e aceita duas renovações paralelas, e são essas as regras do
  ADR-0001 D9. Quando um cliente precisar de refresh (por exemplo, o BFF de um frontend), uma nova decisão define a
  customização. É uma regressão consciente em relação ao ADR-0001 (RF02 e D9).

### D4 — Fluxos

**Login (código de autorização com PKCE)**

```text
cliente  → navegador → /oauth2/authorize?client_id&redirect_uri&scope=openid email&state&code_challenge(S256)
auth-server          → sem sessão: página /login; o usuário digita e-mail e senha NA PÁGINA DO auth-server
auth-server          → abre a sessão dele e redireciona para redirect_uri?code&state
cliente  → /oauth2/token (code + code_verifier)   ← o auth-server confere SHA-256(verifier) = challenge
cliente  ← access token (JWT, 15 min) + id_token
cliente  → app: Authorization: Bearer <access token>
```

O `code` vale poucos minutos e é de uso único; sem o `code_verifier`, quem o interceptar no redirecionamento não
consegue trocá-lo. A sessão do `auth-server` permite um segundo login sem digitar a senha (SSO entre clientes).

**Terminal (fluxo de dispositivo)**

```text
cli → /oauth2/device_authorization (client_id=encurtador-cli) ← user_code, verification_uri, interval
usuário abre a verification_uri no navegador, faz login, digita o user_code e CONFIRMA o acesso da CLI (POST com CSRF)
cli → /oauth2/token (grant_type=urn:ietf:params:oauth:grant-type:device_code) a cada interval ← access token
```

A confirmação explícita impede que um link com o `user_code` de outra pessoa entregue a ela um token de quem já está
com a sessão aberta (D9).

**Sem renovação na v1.** Quando o access token expira, o cliente refaz o fluxo. Com a sessão do `auth-server` aberta,
o `/authorize` devolve um código novo sem pedir a senha; a CLI precisa confirmar um `user_code` novo.

**Logout.** `/connect/logout` encerra a sessão do `auth-server`. O access token já emitido continua válido até o `exp`
(no máximo 15 minutos), como hoje (ADR-0001 D9).

### D5 — Tokens e chaves

**Access token: JWT assinado com RS256**

| Campo | Valor |
|---|---|
| `kid` (cabeçalho) | Identificador da chave de assinatura |
| `iss` | URL pública do `auth-server` (`http://localhost:8090` no local; `AUTH_ISSUER` fora dele), fixada na configuração do SAS |
| `sub` | Identificador estável da credencial (D8); o app o trata como opaco |
| `aud` | `encurtador-api` |
| `email`, `scope` | E-mail atual e escopos concedidos |
| `iat`, `exp` | `exp = iat + 15 min`, como hoje |

- `sub`, `email` e `aud` são definidos por um `OAuth2TokenCustomizer`. Sem ele, o SAS usaria como `sub` o nome do
  principal, que no formulário de login é o e-mail, e como `aud` o `client_id`; e não incluiria o `email`.
- O SAS não grava o cabeçalho `typ`. O app aceita `JWT`, `at+jwt` ou a ausência dele (D6); o `aud` já impede que um
  `id_token` seja aceito como access token.
- A tabela `oauth2_authorization` guarda códigos de autorização, `device_code` e access tokens em claro. Um job diário
  apaga as autorizações expiradas, como o expurgo de refresh tokens de hoje.

**Chave de assinatura**
- RSA 2048, lida de um arquivo PEM (`AUTH_SIGNING_KEY_FILE`) com `AUTH_SIGNING_KEY_ID`. No compose, o arquivo vem de um
  `secret` gerado localmente (`openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`, em `.secrets/`, fora do
  Git). No perfil `local`, uma chave de desenvolvimento explícita, como o segredo JWT de desenvolvimento de hoje
  (ADR-0001 D15).
- O app só conhece chaves públicas: invadir o app não permite emitir tokens.
- Rotação: o JWKS publica a chave nova e a anterior (`AUTH_PREVIOUS_SIGNING_KEY_FILE`); os tokens novos saem com o
  `kid` novo; a anterior é retirada depois de 15 minutos, o tempo máximo de vida de um access token.

### D6 — Validação no app

O `security` (ADR-0002 D4) passa a validar só por chaves públicas. A validação por segredo HMAC é removida junto com o
`identity`.

```yaml
app:
  jwt:
    issuer: http://localhost:8090                      # valor exigido no "iss" (URL pública)
    audience: encurtador-api                           # o token precisa ser para o app
    jwk-set-uri: http://auth-server:8090/oauth2/jwks   # chaves públicas, pela rede interna
    clock-skew: 5s
```

A cada requisição, sem nenhuma chamada ao `auth-server`:

1. extrai o token do `Authorization: Bearer`;
2. acha a chave pública pelo `kid`, num cache em memória;
3. verifica a assinatura RS256 localmente;
4. confere `iss`, `aud`, `exp`/`nbf` (com o `Clock` da aplicação, como no ADR-0001 D9) e o `typ`, se houver;
5. o `@CurrentUser` chama o `UserIdResolver` do `account` (D7), que traduz o `sub` no id local.

Falha nas etapas 1, 3 ou 4 devolve 401 com `WWW-Authenticate: Bearer error="invalid_token"` e corpo problem+json, como
hoje. Os endereços de `issuer` e `jwk-set-uri` são diferentes de propósito: o navegador acessa o `auth-server` por
`localhost:8090`, que vira o `iss`, e o container do app o acessa por `auth-server:8090`.

**Cache de chaves.** O `JWKSource` é montado com o `JWKSourceBuilder` do Nimbus e passado a
`NimbusJwtDecoder.withJwkSource`: cache longo com renovação antecipada, nova busca limitada em taxa quando aparece um
`kid` desconhecido, tolerância a queda (continua usando as chaves em cache quando o `auth-server` não responde) e
timeouts curtos. O padrão do Spring Security (`withJwkSetUri`) guarda as chaves por 5 minutos, sem limite de buscas e
sem tolerância a queda: cinco minutos depois da última busca, com o `auth-server` fora, o app recusaria todos os
tokens.

**Chaves indisponíveis.** Se não houver chave utilizável (cache vazio e `auth-server` fora), a requisição autenticada
responde **503** (`AUTH_UNAVAILABLE`, com `Retry-After`). O padrão do Spring Security seria 500: a falha vira
`AuthenticationServiceException`, que o handler padrão relança. O 503 é ligado por um
`ObjectPostProcessor<BearerTokenAuthenticationFilter>` com um `AuthenticationFailureHandler` próprio, porque a DSL não
oferece esse gancho.

Com o `auth-server` fora, portanto, o app sobe normalmente (o JWKS é buscado sob demanda) e continua aceitando tokens
já emitidos com as chaves em cache. Só novos logins param.

### D7 — Contas no app: módulo `account`

O pacote `identity` do app é removido. No lugar dele entra o `modules/account` (→ `security-api`, `shared`):

- Tabela `accounts`: a `users` renomeada, com a coluna nova `subject` (única, não nula). Os links continuam apontando
  para o mesmo id (`links.owner_id`); nada muda em `link`, `analytics` e `redirect`.
- O app deixa de guardar senha, e-mail e refresh tokens: depois da virada, nenhuma funcionalidade dele usa o e-mail
  (minimização, ADR-0001 RNF08).
- O `account` implementa o `UserIdResolver`: `sub` → id local, com cache em memória de tamanho limitado. Um `sub`
  desconhecido cria a conta no primeiro acesso (`INSERT … ON CONFLICT (subject) DO NOTHING`, seguido da leitura).
  Acessos simultâneos do mesmo usuário novo criam uma única conta.
- O cache é descartado (reinício do app) quando os `subject` forem remapeados, como na troca de provedor (D13).

### D8 — Dados e migração

O `auth-server` usa o schema `auth`, no mesmo PostgreSQL, com histórico Flyway próprio e, fora do ambiente local,
usuário de banco próprio sem acesso ao schema do app.

| Schema | Tabelas | Dono |
|---|---|---|
| `auth` | `credentials` (id, `subject`, e-mail, hash da senha, datas); `oauth2_authorization` (schema fornecido pelo SAS, adaptado ao PostgreSQL: `blob` vira `text` e `timestamp` vira `timestamptz`) | `auth-server` |
| `public` | `accounts`, `links`, `click_events` | app |

**`subject`.** Para os usuários migrados, é o `users.id` atual em texto, o mesmo valor que o app grava em
`accounts.subject`: nenhum remapeamento é necessário. Para usuários novos, é um UUID aleatório, que nunca colide com um
`subject` numérico. O formato é indiferente para o app, que o trata como opaco.

A migração segue o padrão *expand and contract*, para que cada passo seja reversível até o último:

1. **auth-server, V1 (Flyway):** cria o schema `auth`, `credentials` e `oauth2_authorization`. Funciona em banco vazio
   (Testcontainers e CI).
2. **Importação única, fora do Flyway:** o script `auth-server/scripts/importar-usuarios.sql` copia `id`, `email`,
   `password_hash` e as datas de `public.users` para `auth.credentials`, com `subject = id` em texto. É idempotente
   (`ON CONFLICT (subject) DO NOTHING`) e roda antes da V4 do app. Os hashes bcrypt continuam válidos
   (`DelegatingPasswordEncoder`), e os usuários entram com a senha de hoje. Ficar fora do Flyway evita que a migração
   dependa de uma tabela de outro schema que não existe em banco novo.
3. **app, V4** (na mesma fase em que o `identity` sai): `users` vira `accounts`; entra `subject = id` em texto, não nulo
   e único; `password_hash` e `email` passam a aceitar nulo; saem a restrição de unicidade e a de minúsculas do e-mail.
   O provisionamento (D7) grava só `subject` e datas.
4. **app, V5** (depois da virada confirmada): remove `password_hash`, `email` e a tabela `refresh_tokens`.

Os refresh tokens atuais são descartados: na virada, todos fazem login de novo.

### D9 — Proteções no `auth-server`

| Alvo | Chave | Limite |
|---|---|---|
| `POST /login` | IP / e-mail | 10 por minuto / 5 em 15 minutos |
| `POST /register` | IP | 10 por hora |
| `POST /oauth2/token` | IP | 30 por minuto |
| `POST /oauth2/device_authorization` | IP | 10 por minuto |
| Confirmação de `user_code` | IP | 10 por minuto |
| `POST /account/password` | usuário | 5 em 15 minutos |

- Os limites usam o `platform/ratelimit` (ADR-0002 D5), com o mesmo Redis. No app ficam só os de criação de link e de
  redirecionamento.
- O formulário de login do Spring Security não passa por um serviço nosso. Os limites de IP e de e-mail ficam num
  `AuthenticationProvider` próprio, que os consulta antes do bcrypt e delega ao `DaoAuthenticationProvider`. Um 429 nas
  páginas aparece como erro do formulário, e não como problem+json.
- Credenciais e política de senha vêm do `identity` sem mudar de regra (ADR-0001 D9): bcrypt via
  `DelegatingPasswordEncoder`, senha normalizada em NFC com 8 a 64 caracteres e no máximo 72 bytes em UTF-8, mesma
  mensagem para e-mail inexistente e senha errada, e-mail duplicado no cadastro informado (enumeração aceita, ADR-0001
  R7).
- A troca de senha exige a senha atual e encerra as autorizações ativas do usuário.
- **Fluxo de dispositivo:** consentimento obrigatório no `encurtador-cli`, confirmação só por POST com CSRF (um `GET`
  com o `user_code` na URL só preenche o campo) e limite de tentativas de `user_code` (RFC 8628, §5.1 e §5.4). Além
  disso, o `link` passa a recusar o host do `auth-server` como destino, junto com o do próprio encurtador: um link curto
  não pode levar à página de confirmação.
- O `auth-server` tem sessão com cookie (`AUTHSESSION`, `HttpOnly`, `SameSite=Lax`, `Secure` fora do local) e, por
  isso, **CSRF ligado** nas páginas próprias, ao contrário do app, que é stateless. Os endpoints de protocolo do SAS
  não usam CSRF.
- Sessão em memória: uma instância. Mais instâncias exigiriam Spring Session com Redis (fora do escopo).

### D10 — Erros e contrato

- O app mantém problem+json (RFC 9457, ADR-0001 D12).
- Os endpoints de protocolo do `auth-server` respondem erros no formato do OAuth (RFC 6749: `error`,
  `error_description`), que é o que os clientes OAuth esperam. As páginas mostram os erros no próprio formulário.
- **Mudança de contrato no app:** saem `POST /api/v1/auth/{register,login,refresh,logout}` e
  `PUT /api/v1/users/me/password`. Não há clientes externos; README e OpenAPI são atualizados.
- No perfil `local`, o Swagger UI ganha o login pelo fluxo de código com PKCE (`encurtador-dev`). Como ele roda na
  porta 8080 e chama `/oauth2/token` na 8090, o `auth-server` libera CORS para `http://localhost:8080` nesse perfil.

### D11 — Execução local, imagem e CI

- **Compose:** serviço `auth-server` no profile `app`, ao lado do `app`, nas portas `127.0.0.1:8090` e
  `127.0.0.1:8091`. O `app` recebe `APP_JWT_ISSUER` e `APP_JWT_JWK_SET_URI`; o `APP_JWT_SECRET` deixa de existir. O
  `auth-server` recebe datasource (schema `auth`), Redis, `AUTH_ISSUER` e a chave por `secret` (D5).
- **Sem containers:** com duas aplicações, `./mvnw spring-boot:run` na raiz deixa de servir. Cada uma roda com
  `-pl app -am` ou `-pl auth-server -am`. As Run Configurations do IntelliJ passam a ser duas Maven, duas de debug e
  uma composta que sobe as duas aplicações.
- **CI:** o `verify` cobre os dois; o job de imagem constrói as duas imagens.

### D12 — Testes

- **App:** os testes deixam de cadastrar e logar pela API. Um servidor HTTP de teste publica um JWKS com uma chave RSA
  de teste, e `TestTokens` assina com ela. Assim os testes usam o mesmo caminho de produção (busca do JWKS, cache,
  `kid` desconhecido, indisponibilidade), sem precisar do `auth-server`.
- **Cenários do ADR-0001 D14:** os cenários 8 (rotação e reuso de refresh) e 12 (rotações paralelas) deixam de existir
  na v1, porque não há refresh token (D3). O 18 (senha acima de 72 bytes) e a parte de login do 16 (rate limit) passam
  para o `auth-server`. O 1 passa a começar com um token de teste. O 9 (token expirado ou adulterado → 401) continua no
  app.
- **Novos no app:** `sub` desconhecido cria uma conta, e acessos paralelos criam uma só; token de outro emissor ou de
  outra audiência → 401; `typ` ausente, `JWT` ou `at+jwt` aceitos; `kid` desconhecido busca o JWKS de novo, com limite;
  com o JWKS fora por mais tempo que o cache, tokens já conhecidos continuam aceitos; com cache vazio e JWKS fora → 503.
- **`auth-server`:** Testcontainers (PostgreSQL e Redis) e MockMvc para os fluxos de código com PKCE e de dispositivo
  (autenticação do cliente público, consentimento, limite de `user_code`), logout, cadastro, troca de senha, política
  de senha, limites, JWKS, rotação de chave, claims emitidos (`sub`, `email`, `aud`, validade) e expurgo.
- **Ponta a ponta:** um teste com as duas aplicações percorre login → criar link → redirecionar → estatística. A forma
  (Testcontainers com as imagens ou script sobre o compose) é decidida no spike A0.
- O módulo `coverage` (ADR-0002 D7) inclui o `auth-server` no gate.

### D13 — Roteiro de troca pelo Keycloak

Não é implementado agora; fica registrado para mostrar que a troca é possível:

1. No compose, o Keycloak entra no lugar do `auth-server`, com um realm contendo os mesmos clientes (D3) e um mapper de
   audiência `encurtador-api`.
2. No app, `APP_JWT_ISSUER` e `APP_JWT_JWK_SET_URI` passam a apontar para o realm. Nenhuma linha de código muda.
3. Usuários: o Keycloak não usa bcrypt por padrão; a importação exige uma extensão de hash ou redefinição de senha.
   O Keycloak gera um `sub` próprio (UUID). Antes do primeiro acesso, `accounts.subject` dos usuários existentes é
   atualizado para o id do Keycloak, cruzando por e-mail `auth.credentials` (`subject` ↔ e-mail) com o export do
   Keycloak (id ↔ e-mail), e o app é reiniciado para descartar o cache (D7). Sem isso, o provisionamento criaria contas
   novas e os links ficariam sem dono.
4. O módulo `auth-server` sai do repositório.

Para os clientes muda só o endereço do emissor, descoberto por `/.well-known/openid-configuration`; o fluxo já é OIDC.

## 3. Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Manter o login no app (pacote `identity`, ADR-0002) | A senha continua passando pelo app, o segredo HMAC permite fabricar tokens, e a troca futura por um provedor OIDC mudaria o contrato dos clientes do mesmo jeito |
| SAS dentro do app, como um módulo | Um só container, mas o provedor de identidade e o encurtador dividem processo, falhas e escala; a tela de login fica na mesma aplicação que redireciona links |
| Keycloak desde já | Mais infraestrutura (outra JVM e outro banco) e menos controle sobre regras que já existem (política de senha, limites); o objetivo do projeto favorece construir o servidor; a troca continua possível (D13) |
| Fluxo com senha direto (ROPC) para manter `POST /login` em JSON | Não deve ser usado (RFC 9700, §2.4) |
| Refresh token na v1, customizando o SAS (reuso, validade absoluta, concorrência) | Nenhum cliente da v1 precisa dele; a customização mexe no gerador de token e no serviço de autorização e fica para quando houver um BFF |
| Tokens opacos com introspecção | Uma chamada ao `auth-server` por requisição: mais latência e o app para junto com ele |
| HS256 com segredo compartilhado entre `auth-server` e app | O app poderia fabricar tokens; JWKS é o que qualquer provedor OIDC oferece |
| Cópia das credenciais como migração Flyway do `auth-server` | Falha em banco novo (não há `public.users`) e disputa a ordem com a V4 do app |

## 4. Consequências

**Positivas**
- A senha só passa pelo `auth-server`; o app guarda só chaves públicas e não consegue emitir tokens.
- A troca de provedor é configuração e migração de dados, sem código no app (D13).
- Fluxo padrão: SSO entre clientes, fluxo de dispositivo para a CLI, login no Swagger UI, bibliotecas OAuth prontas
  nos clientes.
- O app continua validando tokens com o `auth-server` fora, enquanto tiver as chaves em cache.
- O app deixa de guardar e-mail e senha.

**Negativas**
- Uma segunda aplicação para construir, testar, empacotar e rodar.
- Telas de login, cadastro, troca de senha e confirmação de dispositivo para manter.
- Mudança de contrato: o login deixa de ser JSON.
- Sem refresh token na v1: a CLI confirma um código novo a cada 15 minutos, e os cenários 8 e 12 do ADR-0001 deixam de
  existir.
- Erros dos endpoints de protocolo fora do padrão problem+json do app.
- O `auth-server` tem estado de sessão (uma instância) e guarda em claro os tokens que emitiu até o expurgo.
- Migração de dados em quatro passos entre dois históricos Flyway e um script.

## 5. Plano de implementação

Pré-requisito: ADR-0002 implementado até a M3. Cada fase termina com `./mvnw verify` verde.

| Fase | Entrega | Pronto quando |
|---|---|---|
| A0 | **Spike:** `auth-server` mínimo (SAS, login padrão, um cliente) emitindo um token aceito pelo app. Confere: `OAuth2TokenCustomizer` (`sub`, `email`, `aud`), autenticação de cliente público no fluxo de dispositivo, consentimento do dispositivo, `JWKSourceBuilder` com queda do `auth-server` e o 503, issuer público × JWKS interno no compose, `redirect_uri` com `localhost`, schema do SAS no PostgreSQL, CORS do Swagger UI, forma do teste ponta a ponta | Respostas registradas nas notas deste ADR; decisões ajustadas se preciso |
| A1 | `auth-server`: schema `auth`, login com limites, cadastro, troca de senha, política, chaves RSA e rotação, clientes, logout, expurgo, páginas, script de importação | Testes do `auth-server` verdes |
| A2 | App: `modules/account` com a V4 e o `UserIdResolver`, validação por JWKS com cache e 503, saída do pacote `identity`, das rotas de autenticação e do segredo HMAC, testes com o JWKS de teste, Swagger UI com OAuth2. Numa fase só, porque a V4 renomeia a tabela que o `identity` mapeia | `verify`; `ModularityTest` |
| A3 | Compose, imagem do `auth-server`, CI com as duas imagens, teste ponta a ponta, README, Run Configurations, roteiro da importação | Stack completa em containers: login pela CLI → criar link → redirecionar → estatística |
| A4 | V5 (remove `password_hash`, `email` e `refresh_tokens` do app) e notas de implementação | — |

## 6. Riscos e questões em aberto

| # | Risco / questão | Mitigação |
|---|---|---|
| R1 | O SAS não revoga a sessão no reuso de refresh token, não tem validade absoluta e aceita renovações paralelas (confirmado na revisão 1) | Sem refresh token na v1 (D3); customização numa decisão futura |
| R2 | Falha ao buscar o JWKS vira 500 no tratamento padrão do Spring Security (confirmado na revisão 1) | 503 `AUTH_UNAVAILABLE` (D6), com teste |
| R3 | `iss` público × endereço interno no Docker | `issuer` fixado no SAS e `jwk-set-uri` separado (D6); validar no A0 |
| R4 | Gestão da chave privada fora do local (sem cofre de segredos) | Arquivo PEM por `secret`; procedimento de rotação documentado (D5) |
| R5 | A importação das credenciais exige leitura em `public.users` | Script único e idempotente, fora do Flyway (D8); os dados atuais são de desenvolvimento |
| R6 | Exclusão de conta (LGPD) passa a envolver duas aplicações | Fora do escopo; quando entrar, o `auth-server` remove a credencial e avisa o app |
| R7 | Telas próprias sem verificação de e-mail | Fora do escopo, como no ADR-0001 |
| R8 | Phishing com o `user_code` do fluxo de dispositivo | Consentimento, confirmação por POST com CSRF, limite de tentativas e bloqueio do host do `auth-server` nos links (D9) |
| R9 | Validação de `redirect_uri` com `localhost` no SAS 7.1 (não confirmada na revisão) | Testar no A0 |
| R10 | Tokens em claro em `oauth2_authorization` | Schema isolado com usuário próprio, sem refresh token, expurgo diário (D5) |

## 7. Impacto nos ADRs anteriores

| Decisão | Efeito |
|---|---|
| ADR-0001 D1 | Entram o starter do Spring Authorization Server e o Thymeleaf, no `auth-server` |
| ADR-0001 D6 | `users` vira `accounts` (sem senha nem e-mail); credenciais e autorizações vão para o schema `auth` |
| ADR-0001 D9 | **Substituída por este ADR**, exceto a tabela de autorização das rotas do app e as respostas 401/403 em ProblemDetail, que continuam |
| ADR-0001 D10 | O `HostClassifier` também recusa o host do `auth-server` (D9) |
| ADR-0001 D11 | Limites de login, cadastro, refresh e troca de senha passam para o `auth-server`, que ganha os de fluxo de dispositivo (D9) |
| ADR-0001 D12 | Saem as rotas de autenticação do app; erros de protocolo no formato OAuth no `auth-server` |
| ADR-0001 D13 | O `auth-server` segue a mesma observabilidade e nunca registra credenciais nem códigos (D2) |
| ADR-0001 D14 | Cenários redistribuídos; 8 e 12 deixam de existir na v1 (D12) |
| ADR-0001 D15 | `APP_JWT_SECRET` deixa de existir; entram `AUTH_SIGNING_KEY_FILE`, `AUTH_ISSUER`, `APP_JWT_ISSUER`, `APP_JWT_JWK_SET_URI` e o serviço `auth-server` no compose |
| ADR-0001 RF02 | Login e logout passam para o `auth-server`; o refresh fica fora da v1 (D3) |
| ADR-0001 R5 | Resolvido: RS256 com JWKS |
| ADR-0001 R8 | Deixa de existir na v1, sem refresh token |
| ADR-0002 D1, D2 | O pacote `identity` sai do `app`; entram `modules/account` (→ `security-api`, `shared`) e `auth-server/` (→ `ratelimit`, `shared`) |
| ADR-0002 D4 | O `security` passa a validar só por JWKS |
| ADR-0002 D5 | As regras de limite do `identity` saem com ele; o `auth-server` registra o filtro com as próprias regras |
| ADR-0002 D7 | O `coverage` inclui o `auth-server`, também com o jar `exec` |
| ADR-0002 D8 | As migrações do app seguem no `app`; o `auth-server` tem histórico próprio no schema `auth` |
| ADR-0002 D9 | Dockerfile construindo as duas aplicações; Run Configurations para as duas |

As marcações correspondentes foram feitas nos ADRs anteriores no aceite deste ADR (2026-11-02).

## 8. Registro de revisão

### Revisão 1 — 2026-11-02 (revisor de arquitetura independente)

A revisão cobriu este ADR e o ADR-0002 juntos. Veredito: **aprovado com ressalvas** (3 bloqueantes, 9 importantes, 8
sugestões no total). O revisor conferiu as afirmações técnicas no código-fonte do Spring Security 7.1.2, nos plugins
do Maven e nas RFCs; as afirmações sobre o repositório foram reconferidas. Achados que tocam este ADR:

| ID | Severidade | Tema | Decisão | Onde |
|---|---|---|---|---|
| R-01 | Bloqueante | Jar executável no gate de cobertura | Aceito: `auth-server` também com o classificador `exec` | D2, ADR-0002 D7 |
| R-02 | Bloqueante | Cópia das credenciais no Flyway, `password_hash not null`, unicidade do e-mail, colunas identity | Aceito: importação fora do Flyway, V4 relaxa as restrições, o app não guarda e-mail | D7, D8 |
| R-03 | Bloqueante | Fluxo de dispositivo sem consentimento permite entregar um token da vítima por link | Aceito | D3, D4, D9 |
| R-04 | Importante | Cache padrão do JWKS (5 min, sem limite, sem tolerância a queda) e 500 com o JWKS fora | Aceito | D6, D12 |
| R-05 | Importante | O SAS não atende às regras de refresh do ADR-0001 | Aceito: sem refresh token na v1, regressão registrada; o `encurtador-web` sai | D3, D4, §3, §6 |
| R-06 | Importante | Cliente público no fluxo de dispositivo exige autenticação própria | Aceito | D3 |
| R-07 | Importante | `auth-server` não escaneia a plataforma; o advice global responderia às páginas | Aceito | D2, ADR-0002 D11 |
| R-08 | Importante | Build vermelho entre A2 e A3 | Aceito: A2 e A3 viram uma fase | §5 |
| R-09 | Importante | Requisito 2 (centralizar) e as regras do `auth-server` | Aceito: interpretação escrita no ADR-0002 §1.1; o `auth-server` tem a própria configuração | ADR-0002 §1.1 |
| R-10 | Importante | Limites no formulário de login, 429 em HTML, `sub` padrão do SAS é o e-mail | Aceito | D5, D9 |
| R-11 | Importante | Porta 9000 do Kafdrop, cookies de `localhost`, chave no compose | Aceito: portas 8090/8091, cookie próprio, chave por `secret` | D2, D5, D11 |
| R-12 | Importante | Tabela de impacto incompleta | Aceito | cabeçalho, §7 |
| R-15 | Sugestão | Cache do `UserIdResolver` sem limite e contradição com o remapeamento | Aceito | D7, D13 |
| R-16 | Sugestão | Chave fixa nos testes não exercita o JWKS | Aceito: JWKS de teste por HTTP | D12 |
| R-17 | Sugestão | O SAS não grava `typ` | Aceito | D5, D6 |
| R-18 | Sugestão | Swagger UI precisa de CORS; callback de terceiro | Aceito: CORS no perfil `local`, cliente só no perfil `local` | D3, D10 |
| R-20 | Sugestão | `encurtador-web` sem uso | Aceito | D3 |

**Achado próprio, na incorporação:** a versão anterior dava aos usuários novos o id da credencial como `sub`, o que
colidiria com os `subject` numéricos dos usuários migrados. Usuários novos passam a receber um UUID (D8).

**Afirmações reconferidas pelo revisor:** o `aud` padrão do SAS é o `client_id`; o access token padrão vale 5
minutos; faltavam `/oauth2/introspect` e `/.well-known/oauth-authorization-server` na lista de endpoints; o schema do
SAS precisa de ajuste no PostgreSQL; os hashes `{bcrypt}` atuais continuam válidos; a RFC 9700 proíbe o ROPC; o OAuth
2.1 ainda é rascunho.

## Referências

- [RFC 9700 — Best Current Practice for OAuth 2.0 Security](https://datatracker.ietf.org/doc/html/rfc9700)
- [RFC 8628 — OAuth 2.0 Device Authorization Grant](https://datatracker.ietf.org/doc/html/rfc8628)
- [Spring Authorization Server moving to Spring Security 7.0](https://spring.io/blog/2025/09/11/spring-authorization-server-moving-to-spring-security-7-0)
- [How-to: Authenticate using a Single Page Application with PKCE](https://docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html)
- [Edge Authentication and Token-Agnostic Identity Propagation (Netflix)](https://netflixtechblog.com/edge-authentication-and-token-agnostic-identity-propagation-514e47e0b602)
- [Deprecation of Keycloak adapters](https://www.keycloak.org/2022/02/adapter-deprecation.html)
