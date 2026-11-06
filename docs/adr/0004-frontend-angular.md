# ADR-0004 — Frontend em Angular

| Campo | Valor |
|---|---|
| Status | **Aceito** (2026-11-04), após a revisão 1 (§8) · **implementado** (W0 a W6 e WA, 2026-11-06; notas de implementação em §8) · D2, D11 e D12 ajustadas pelo ADR-0005 |
| Data | 2026-11-04 |
| Escopo | Painel web do encurtador: conta (cadastro, login, logout, troca de senha), gestão de links e estatísticas |
| Depende de | ADR-0003 (servidor de autorização, ainda não implementado) e ADR-0002 (estrutura em módulos) |
| Ajusta | ADR-0001 §4, RF02, R8, D8, D11, D12, D13 e D14; ADR-0002 D1, D2, D7 e D9; ADR-0003 D3, D4, D5, D9, D11, R10 e §5 |
| Decisores | Dono do projeto (aprovação) · autor da proposta · revisor de arquitetura independente (§8) |

**Direcionadores.** Stack definida pelo dono do projeto: Angular. A senha do usuário não pode passar pelo frontend nem
pelo app (ADR-0003); nenhum token fica acessível ao JavaScript do navegador; o painel não pode impedir a troca do
provedor de identidade (ADR-0003 D13); qualidade verificável por testes automatizados, como no backend.

**Vocabulário.** *SPA* é a aplicação Angular que roda no navegador; *BFF* (*backend for frontend*) é o servidor que a
entrega e fala com o resto em nome dela; *app* é a aplicação HTTP do encurtador (rotas `/api/v1/**` e `/{code}`);
*auth-server* é o servidor de autorização do ADR-0003.

---

## 1. Contexto

### 1.1 O que o painel precisa fazer

1. Cadastro de usuário, login, logout e troca de senha.
2. Uma página para gerenciar os links do usuário.
3. Uma página com as estatísticas do usuário.

O ADR-0001 (§4) deixou a interface web fora do escopo da v1; este ADR a traz para dentro.

### 1.2 O que já existe

| Recurso | Rota no app | Observação |
|---|---|---|
| Criar link | `POST /api/v1/links` | `url`, `alias` opcional, `expiresAt` opcional (padrão 30 dias, máximo 365); 409 `ALIAS_TAKEN` ou `LINK_QUOTA_EXCEEDED` (100 ativos) |
| Listar links | `GET /api/v1/links?page&size` | Mais recentes primeiro; inclui os expirados, não os removidos |
| Consultar link | `GET /api/v1/links/{code}` | `code`, `shortUrl`, `targetUrl`, `status` (`ACTIVE` ou `EXPIRED`), `createdAt`, `expiresAt` |
| Remover link | `DELETE /api/v1/links/{code}` | O código nunca é reaproveitado; o redirecionamento passa a responder 410 |
| Estatísticas de um link | `GET /api/v1/links/{code}/stats?from&to&tz` | Total, série diária e 10 principais referers; até 90 dias; link removido responde 404 |
| Estatísticas do usuário | — | **Não existe** |

Todo erro é problem+json (RFC 9457) com um `code` estável e, em `VALIDATION_FAILED`, a lista `errors[]` por campo
(ADR-0001 D12).

### 1.3 Restrições herdadas do ADR-0003

- Login, cadastro e troca de senha são do `auth-server`, em páginas próprias dele. O app deixa de ter
  `/api/v1/auth/*` e só valida tokens.
- O fluxo com senha direto (ROPC) não pode ser usado (RFC 9700, §2.4): um formulário de login no Angular que envie a
  senha por JSON não é uma opção.
- O SAS não emite refresh token para cliente público, e a própria documentação dele recomenda um BFF para SPAs.
- O ADR-0003 tirou da v1 o cliente `encurtador-web` (de um BFF futuro) e o refresh token, porque o SAS não cumpre as
  regras de rotação do ADR-0001 D9; uma decisão nova definiria o refresh token quando houvesse um cliente que
  precisasse dele. Esta é essa decisão (D4).

### 1.4 Como os requisitos são interpretados

| Requisito | Interpretação adotada |
|---|---|
| Cadastro, login, logout, troca de senha | O painel oferece os quatro fluxos. Os formulários que recebem a senha são as páginas do `auth-server`, para onde o painel leva o usuário e de onde ele volta; o login e o logout são conduzidos pelo BFF. A senha nunca passa pela SPA, pelo BFF nem pelo app |
| Estatísticas por usuário | Uma visão agregada dos links do usuário que não foram removidos (total, série diária, links e referers mais acessados), com acesso às estatísticas de cada link, que já existem |

## 2. Decisões

### D1 — Arquitetura: SPA Angular atrás de um BFF

```text
navegador ── HTTPS, cookie de sessão, mesma origem ──→ bff (porta 8070)
   │                                                   ├─ entrega a SPA (arquivos estáticos)
   │                                                   ├─ /oauth2/**, /login/**, /bff/**: login, logout e sessão
   │                                                   └─ /api/**  ── Bearer (access token) ──→ app (8080)
   │
   ├─ redirecionamentos de login, cadastro, troca de senha e logout ──→ auth-server (8090)
   └─ links curtos (/{code}) ──→ app (8080), em outro domínio
```

- O navegador só conversa com o BFF, na mesma origem: sem CORS e sem token no navegador.
- O BFF é um cliente OAuth **confidencial** (`encurtador-web`): guarda o access token e o refresh token na sessão do
  servidor, renova o access token quando ele expira e o acrescenta às chamadas que repassa ao app.
- O app continua sendo só servidor de recurso (ADR-0003 D6) e não muda, exceto pelo endpoint de estatísticas (D7). O
  `auth-server` continua dono das credenciais.
- **O painel tem domínio próprio** (por exemplo, `painel.encurta.dor`), separado do domínio dos links curtos
  (`encurta.dor`) e do `auth-server` (`auth.encurta.dor`). No mesmo domínio, as rotas do painel (`/links`,
  `/estatisticas`) disputariam o espaço de um segmento do redirecionamento (`/{code}`), e o cookie de sessão iria junto
  em todo clique de link curto. O `HostClassifier` já recusa subdomínios do encurtador como destino de link, então o
  painel e o `auth-server` não podem ser alvo de um link curto. No ambiente local, as portas fazem o papel dos
  domínios.

### D2 — Estrutura no repositório

> **Ajustada pelo ADR-0005** (2026-11-06): o `web` fica em `frontend/web`, e o `bff`, em `apps/bff`; o Playwright sai do
> `web` (D11).

Dois módulos novos, na raiz, ao lado das outras aplicações (ADR-0002 D1):

```text
encurtador-url/
├── web/                          encurtador-url-web   workspace Angular empacotado como jar
│   ├── pom.xml                   frontend-maven-plugin: npm ci, lint, test e build no ./mvnw verify
│   ├── package.json, package-lock.json, angular.json, tsconfig*.json, eslint.config.js, public/
│   └── src/app/
│       ├── core/                 sessão, interceptors, tratamento de erros, guards, layout
│       ├── shared/               componentes reaproveitados: gráfico, painel de estatísticas, confirmação, status
│       └── features/
│           ├── home/             página pública: Entrar e Criar conta
│           ├── links/            lista, criação, detalhe e remoção
│           ├── stats/            estatísticas do usuário
│           └── account/          e-mail, trocar senha, sair
├── bff/                          encurtador-url-bff   aplicação Spring Boot (portas 8070 e 8071)
│   └── src/main/java/br/com/encurtador/bff/
│       ├── session/              login e logout OIDC, sessão, /bff/session, /bff/register, /bff/logout
│       ├── proxy/                repasse de /api/** ao app com o access token
│       └── web/                  entrega da SPA, cabeçalhos de segurança, rotas da SPA para index.html
```

| Módulo | Depende de |
|---|---|
| web | — (só o Angular e os pacotes do npm) |
| bff | web, shared, ratelimit |

- O build do Angular gera os arquivos estáticos dentro do jar do `web`, em `META-INF/resources/`, de onde o Spring Boot
  os serve. O `bff` não depende do `security`, dos módulos de negócio nem do `app`.
- No `web`, cada pasta de `features/` só importa de `core/` e `shared/`, nunca de outra feature: o painel de
  estatísticas de um link, usado em `links/` e em `stats/`, fica em `shared/`. A regra é verificada pelo ESLint
  (`no-restricted-imports`).

### D3 — Stack do Angular

| Tema | Decisão |
|---|---|
| Versão | Angular 22 (22.2 hoje), atualizado com `ng update` a cada versão maior |
| Componentes | Standalone, com o controle de fluxo novo (`@if`, `@for`) |
| Detecção de mudanças | Sem Zone.js (*zoneless*, o padrão de projetos novos), com estado em signals |
| Estado | Serviços com signals; sem NgRx, que não se paga num painel deste tamanho |
| HTTP | `HttpClient` com `withFetch()` e interceptors funcionais |
| Rotas | Rotas com carregamento sob demanda por feature e guards funcionais |
| Formulários | Signal Forms (`@angular/forms/signals`), estáveis desde o Angular 22 |
| Componentes visuais | Angular Material 22 (Material 3) e CDK, pela acessibilidade pronta; tema próprio com tokens de cor, claro e escuro; fontes servidas pelo próprio BFF |
| Gráficos | Chart.js 4, por um componente próprio fino (sem biblioteca de integração) |
| Build | `@angular/build` (esbuild), com `inlineCritical: false` (D9); sem SSR: é um painel autenticado, sem necessidade de SEO |
| Testes | Vitest, o executor padrão do Angular (D11) |
| Linguagem | TypeScript em modo estrito; ESLint (`angular-eslint`) e Prettier |

### D4 — Autenticação no navegador

**Registro do cliente.** O `encurtador-web` é registrado no `auth-server` como cliente confidencial: código de
autorização com PKCE e segredo de cliente. No BFF, a `ClientRegistration` é montada em código, e não por
`issuer-uri`: a descoberta no startup impediria o BFF de subir sem o `auth-server`, e devolveria endpoints públicos
inalcançáveis de dentro do container. A autorização e o logout usam o endereço público do `auth-server`; o token, o
JWKS e o userinfo, o interno, como o app faz no ADR-0003 D6. O PKCE é ligado explicitamente
(`requireProofKey(true)`), porque o Spring Security só o liga sozinho para cliente público.

**Refresh token.** O `encurtador-web` recebe refresh token, guardado só na sessão do BFF, **sem rotação**
(`reuse-refresh-tokens: true`, o padrão do SAS) e com validade de 30 dias desde o login, que é também a validade
absoluta da sessão. A RFC 9700 (§2.2.2) só exige rotação ou vínculo ao remetente para clientes públicos; aqui o token
só serve com o segredo do cliente, que fica no servidor. Isso desvia do ADR-0001 D9 (rotação com detecção de reuso)
para este cliente, e elimina o que impedia o refresh token no SAS (ADR-0003 R1): sem rotação, duas renovações
paralelas são válidas e não há reuso a detectar. O refresh token é revogado no logout, e a troca de senha encerra as
autorizações do usuário (ADR-0003 D9).

**Sessão.** Em memória, numa instância, como a do `auth-server` (ADR-0003 D9). Um reinício do BFF pede login de novo,
que é silencioso enquanto a sessão do `auth-server` valer. A sessão expira após 2 horas sem uso. O cookie tem nome
próprio (`WEBSESSION` no local, `__Host-WEBSESSION` fora dele, porque os cookies de `localhost` não se separam por
porta), `HttpOnly`, `SameSite=Lax` e `Secure` fora do ambiente local. O início do login (`/oauth2/authorization/**` e
`/bff/register`) tem limite de 20 por minuto por IP, pelo `platform/ratelimit`: cada início cria uma sessão anônima.

**CSRF.** `csrf(c -> c.spa())` do Spring Security 7: o token fica num cookie legível (`XSRF-TOKEN`, ou
`__Host-XSRF-TOKEN` fora do local), e o `HttpClient` do Angular o devolve no cabeçalho `X-XSRF-TOKEN` em toda
requisição para a mesma origem com método diferente de GET e HEAD. `SameSite=Lax` não basta sozinho, porque o painel
e o `auth-server` são do mesmo site que o encurtador.

**Fluxos**

| Fluxo | Como funciona |
|---|---|
| Saber quem está logado | Ao iniciar, a SPA chama `GET /bff/session`: 200 com o e-mail (do `id_token`), ou 401. Os guards de rota usam essa resposta |
| Login | "Entrar" navega a página inteira (não é uma chamada HTTP) para `/oauth2/authorization/encurtador?returnTo=/links`. O BFF redireciona ao `auth-server`, recebe o código em `/login/oauth2/code/encurtador`, abre a sessão e volta para o `returnTo` |
| `returnTo` | O Spring Security não tem esse parâmetro: um resolvedor de pedido de autorização próprio o lê, valida e guarda na sessão, e um tratador de sucesso redireciona para ele. Só vale se começar com `/`, não começar com `//` nem `/\` e for uma das rotas da SPA (D6); senão, o destino é `/links` |
| Cadastro | "Criar conta" navega para `/bff/register`, que redireciona à página de cadastro do provedor, cuja URL vem da configuração do BFF, com a volta apontando para o início do login do painel. O `auth-server` deixa o usuário logado ao fim do cadastro e só aceita voltas para endereços do painel registrados; o login seguinte é silencioso, e o usuário chega ao painel já logado. Trocar de provedor (ADR-0003 D13) muda só a URL configurada |
| Troca de senha | Link para a página `/account/password` do `auth-server`, que pede login se a sessão dele tiver expirado e volta para `/conta`, um endereço fixo configurado no `auth-server`. A troca encerra as autorizações do usuário: na próxima renovação o BFF recebe erro, encerra a sessão e a SPA leva o usuário ao login |
| Logout | "Sair" faz `POST /bff/logout` pelo `HttpClient` (com CSRF). O BFF revoga o refresh token (`/oauth2/revoke`), encerra a sessão e responde 200 com a URL de logout do `auth-server` (`/connect/logout` com `id_token_hint` e `post_logout_redirect_uri`). A SPA navega para ela (`location.assign`), o `auth-server` encerra a própria sessão e volta à página inicial do painel. Sem a navegação de página inteira, a sessão do `auth-server` sobreviveria e o próximo "Entrar" seria silencioso |
| Sessão expirada | `/api/**` e `/bff/**` sem sessão válida respondem 401 em problem+json, nunca um redirecionamento (um ponto de entrada próprio para essas rotas). A SPA mostra o aviso e o botão "Entrar", guardando a rota atual em `returnTo` |

### D5 — Repasse de `/api/**` ao app

- O BFF repassa ao app só as rotas que o painel usa (`/api/v1/links/**` e `/api/v1/stats`), com o mesmo método,
  caminho, query e corpo. Qualquer outra rota de `/api/**` responde 404 no BFF.
- O access token entra pelo `OAuth2ClientHttpRequestInterceptor` do Spring Security num `RestClient`, que também
  renova o token quando ele vence. Cookies, `Authorization` e qualquer cabeçalho `X-Forwarded-*` vindos do navegador
  nunca são repassados.
- A resposta do app volta com status, corpo e os cabeçalhos que a SPA usa (`Content-Type`, `Location`, `Retry-After`,
  `Cache-Control`). Os erros do app já são problem+json e passam sem tradução.
- App fora do ar ou lento: 503 em problem+json (`UPSTREAM_UNAVAILABLE`), com timeouts curtos de conexão e leitura.
- O BFF é escrito só com o Spring Security e o `RestClient`, sem o Spring Cloud Gateway: a linha 2025.1 da Spring
  Cloud é para o Spring Boot 4.0, e a 2026.0, que acompanha o 4.1, ainda está em RC.
- O app não muda o tratamento de IP. A única regra por IP dele depois do ADR-0003 é a do redirecionamento, que não
  passa pelo BFF; a criação de link é limitada por usuário. Confiar em `X-Forwarded-For` abriria a falsificação do IP
  no `/{code}`, porque a lista padrão de proxies confiáveis inclui as redes privadas do Docker.

### D6 — Páginas e rotas da SPA

| Rota | Acesso | Conteúdo | API |
|---|---|---|---|
| `/` | pública | Apresentação, "Entrar" e "Criar conta"; logado, vai para `/links` | `GET /bff/session` |
| `/links` | logado | Lista paginada (20 por página) com status (ativo ou expirado), validade, botão de copiar a URL curta e de abrir o destino; criação num diálogo (URL, alias e validade opcionais); remoção com confirmação, avisando que o código não volta a valer | `GET`, `POST`, `DELETE /api/v1/links` |
| `/links/:code` | logado | Dados do link e as estatísticas dele, com seletor de período | `GET /api/v1/links/{code}` e `/stats` |
| `/estatisticas` | logado | Estatísticas do usuário (D7): totais, série diária, links e referers mais acessados, com seletor de período | `GET /api/v1/stats` |
| `/conta` | logado | E-mail, "Trocar senha" (página do `auth-server`) e "Sair" | `GET /bff/session` |
| `**` | — | Página de não encontrado | — |

- O destino de um link é sempre mostrado como texto e aberto em nova aba com `rel="noopener noreferrer"`, nunca por
  navegação automática. O botão "Abrir" abre o destino, e não a URL curta, para o próprio dono não contar cliques.
- O período padrão das estatísticas é de 30 dias, no fuso do navegador (`Intl.DateTimeFormat`), com opções de 7, 30 e
  90 dias e de outro fuso. Se o app recusar o fuso do navegador (400), a SPA usa `America/Sao_Paulo`, o fuso padrão do
  app, e avisa.

### D7 — Estatísticas do usuário: endpoint novo no `analytics`

O painel não monta a visão agregada somando as estatísticas de cada link: seriam até 100 requisições por página. O
módulo `analytics` ganha um endpoint novo, com as mesmas regras do de um link (período de até 90 dias, fuso IANA
aceito pelo PostgreSQL, dias sem clique preenchidos com zero):

```text
GET /api/v1/stats?from=2026-10-06&to=2026-11-04&tz=America/Sao_Paulo
→ 200 {
    "totalClicks": 1520, "periodClicks": 312,
    "from": "2026-10-06", "to": "2026-11-04", "timezone": "America/Sao_Paulo",
    "daily":        [ { "date": "2026-10-06", "clicks": 9 }, … ],
    "topLinks":     [ { "code": "meu-artigo", "clicks": 120, "status": "ACTIVE" }, … ],
    "topReferrers": [ { "host": "exemplo.com", "clicks": 80 }, { "host": null, "clicks": 41 }, … ]
  }
```

- Conta só os links do usuário que não foram removidos (ativos e expirados), como já fazem as estatísticas de um link:
  o ADR-0001 D8 tira das estatísticas os cliques de links removidos.
- `topLinks` e `topReferrers` trazem até 10 itens; `status` é `ACTIVE` ou `EXPIRED`.
- Nenhum módulo lê tabelas de outro (ADR-0001 D2): o `LinkLookup` do módulo `link` ganha `ownedLinks(ownerId)`, que
  devolve id, código e status dos links não removidos do usuário, e o `analytics` consulta `click_events` com
  `link_id = any(:ids)`, usando os índices que já existem.
- Só os links ativos têm teto (100); os expirados se acumulam. A lista de ids não tem limite, o que é aceitável nesta
  escala, e o endpoint tem limite de 30 por minuto por usuário: o `analytics` declara a regra `user-stats` (ADR-0002
  D5) e passa a depender do `platform/ratelimit`.

### D8 — Erros e mensagens

- Um mapa central traduz cada `code` do problem+json numa mensagem em português (`ALIAS_TAKEN`, `LINK_QUOTA_EXCEEDED`,
  `LINK_NOT_FOUND`, `CODE_GENERATION_EXHAUSTED`, `RATE_LIMITED`…). Para um código desconhecido, a SPA mostra o
  `detail`, que o backend já escreve em português e sem detalhe interno (ADR-0001 D12).
- Em `VALIDATION_FAILED`, cada item de `errors[]` vira o erro do campo correspondente no formulário.
- 401: o aviso de sessão expirada de D4. 429: a mensagem com o tempo de espera do `Retry-After`. 503: aviso de serviço
  indisponível, com nova tentativa manual.
- Nenhum erro é engolido em silêncio: todo erro não tratado chega a um handler global, que mostra uma mensagem
  genérica e registra no console só o `code` e o `instance`.

### D9 — Segurança no navegador

- Nenhum token em JavaScript, `localStorage` ou `sessionStorage`: o navegador só tem o cookie de sessão `HttpOnly` e
  o cookie de CSRF.
- O BFF envia esta `Content-Security-Policy`:

  ```text
  default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self';
  connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'
  ```

  Os scripts ficam restritos à própria origem. Os estilos aceitam `'unsafe-inline'` porque o Angular injeta `<style>`
  dos componentes em tempo de execução; a alternativa, um nonce por requisição, exigiria que o BFF reescrevesse o
  `index.html` a cada resposta. O build desliga o CSS crítico em linha (`inlineCritical: false`), que poria script em
  linha no `index.html`, e não usa o `autoCsp`, ainda experimental.
- Também: `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin` e, fora do local,
  `Strict-Transport-Security`.
- Nada de `innerHTML` com dado do usuário nem de `bypassSecurityTrust*`: o Angular escapa tudo o que é interpolado.
- Arquivos com hash no nome são servidos com cache de um ano (`immutable`); o `index.html`, com `no-cache`, para uma
  versão nova chegar na hora.

### D10 — Acessibilidade, idioma e responsividade

- Alvo WCAG 2.2 nível AA, no painel e nas páginas do `auth-server` por onde o usuário passa (login, cadastro, troca de
  senha): navegação completa por teclado, foco visível, rótulos em todos os campos, contraste conferido nos temas claro
  e escuro, gráficos acompanhados de tabela com os mesmos números.
- A cada troca de rota, o título do documento muda e o foco vai para o título principal da página.
- Só português do Brasil: `LOCALE_ID` `pt-BR`, datas e números pelos pipes do Angular. Sem framework de tradução;
  textos ficam nos templates.
- Layout responsivo a partir de 360 px de largura; tema claro e escuro conforme a preferência do sistema.

### D11 — Testes e qualidade

> **Ajustada pelo ADR-0005** (2026-11-06): o Playwright fica em `e2e/playwright`.

| Nível | Ferramenta | Cobre |
|---|---|---|
| Unitário e de componente | Vitest e `HttpTestingController` | Serviços, interceptors, guards, mapa de erros, formulários, fallback do fuso e componentes de cada página |
| BFF | Testes do Spring Boot: `oauth2Login()` do `spring-security-test`, `MockRestServiceServer` para o app e um servidor de token falso (MockWebServer) para o `auth-server` | Sessão, `/bff/session`, CSRF, 401 sem sessão, validação do `returnTo`, cadastro, logout com revogação, repasse e lista de rotas permitidas, renovação do token, cabeçalhos de segurança, rotas da SPA |
| Ponta a ponta | Playwright contra a stack do compose (`auth-server`, app, BFF) | Cadastro → login → criar link → estatísticas → remover → trocar senha → sair, com axe em cada página, inclusive as do `auth-server`, e os caminhos principais só por teclado |

- Gate de cobertura do frontend: 80% de linhas, medido pelo Vitest (`coverageThresholds`), à parte do JaCoCo. O BFF
  entra no gate do módulo `coverage` (ADR-0002 D7).
- `./mvnw verify` roda o lint, os testes e o build do Angular pelo `frontend-maven-plugin`. O Playwright roda num job
  próprio do CI, depois do build das imagens.

### D12 — Build, execução local e entrega

> **Ajustada pelo ADR-0005** (2026-11-06): o Dockerfile copia `frontend/web/`; no compose, o BFF chama a API em
> `http://api:8080`; o BFF sobe com `./mvnw -pl apps/bff -am spring-boot:run`.

- **Node:** o Angular 22 aceita Node `^22.22.3`, `^24.15.0` ou `>=26.0.0`; o Node 25 instalado nesta máquina não é
  suportado. O `.mise.toml` passa a fixar o Node 26 (LTS), e o `frontend-maven-plugin` instala a mesma versão no CI e
  no build da imagem, num diretório dentro do cache do Maven.
- **`bff`:** declara o `spring-boot-maven-plugin` com o classificador `exec` e o `build-info`, e
  `spring-boot.run.skip=false` (ADR-0002 D9). O `./mvnw spring-boot:run` na raiz continua subindo o app, o primeiro do
  reactor; o BFF sobe com `./mvnw -pl bff -am spring-boot:run`.
- **Rotas da SPA:** um controller do BFF entrega o `index.html` para as rotas de D6. Sem ele, o handler do `shared`
  transformaria essas rotas em 404 problem+json (ADR-0002, notas de implementação).
- **Configuração do BFF:** `APP_BASE_URL` (endereço público do painel, exigido pelo `shared`), `BFF_API_URL` (app,
  endereço interno), `BFF_AUTH_PUBLIC_URL` e `BFF_AUTH_INTERNAL_URL` (`auth-server`), `BFF_CLIENT_ID` e
  `BFF_CLIENT_SECRET` (`encurtador-web`), `BFF_REGISTRATION_URL` (página de cadastro do provedor) e
  `SPRING_DATA_REDIS_HOST` (o Redis do rate limit, que só guarda contadores, nunca a sessão).
- **Observabilidade:** como no ADR-0001 D13: actuator na porta de gestão (8071), `/livez` e `/readyz` na pública
  (`/livez` é o `HEALTHCHECK` da imagem) e logs JSON ECS. Os logs nunca registram cookies, tokens nem corpos de
  requisição.
- **Desenvolvimento:** `npm start` no `web` sobe o `ng serve` na porta 4200, com proxy para o BFF (8070) em `/api`,
  `/bff`, `/oauth2` e `/login`. O perfil `local` do `auth-server` registra os endereços de retorno de `localhost:4200`
  e `localhost:8070`.
- **Imagem:** o mesmo Dockerfile (`--build-arg MODULE=bff --build-arg HTTP_PORT=8070 --build-arg
  MANAGEMENT_PORT=8071`, ADR-0002 D9), que passa a copiar a pasta `web/` inteira (o `package.json`, o lock, o
  `angular.json`, os `tsconfig`, a configuração do ESLint e o `public/`, além do `src/`) e a usar cache do npm na
  camada de build.
- **Ignorados:** `.gitignore` e `.dockerignore` passam a ignorar `node_modules/`, `web/node/`, `.angular/` e `dist/`.
- **Compose:** serviço `bff` no profile `app`, nas portas `127.0.0.1:8070` e `127.0.0.1:8071`, com o segredo do
  cliente `encurtador-web` por variável de ambiente.
- **CI:** o `verify` cobre o `web` e o `bff`; o job de imagem constrói também a do BFF; o job de ponta a ponta sobe a
  stack e roda o Playwright.

## 3. Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| SPA como cliente público (PKCE no navegador, por exemplo `angular-oauth2-oidc`) | O token fica ao alcance de qualquer XSS; o SAS não dá refresh token a cliente público, e a renovação silenciosa por iframe depende de cookies de terceiros |
| Telas de login e cadastro no Angular, chamando a API JSON | Exige o fluxo com senha direto (proibido pela RFC 9700) e desfaz o ADR-0003 |
| Refresh token com rotação e detecção de reuso, como no ADR-0001 D9 | O SAS não faz nenhuma das duas; customizá-lo exigiria guardar hashes aposentados, atualização condicional e um lock entre instâncias, para um token que só serve com o segredo do cliente |
| Cadastro por `prompt=create` (OpenID Connect) | O SAS só conhece `none`, `login`, `consent` e `select_account`; a URL de cadastro configurada dá o mesmo resultado sem customizar a validação do pedido de autorização |
| Sessão do BFF no Redis (Spring Session) | Os tokens ficariam serializados num Redis sem controle de acesso e visível no redis-commander; o Redis do projeto é cache, não fonte de verdade (ADR-0001 D7); uma instância não precisa de sessão compartilhada |
| CSP com nonce por requisição | O BFF teria de reescrever o `index.html` a cada resposta; com os scripts restritos, o ganho sobre `'unsafe-inline'` nos estilos é pequeno |
| BFF com Spring Cloud Gateway | Sem versão estável alinhada ao Spring Boot 4.1; o repasse que o painel precisa cabe em pouco código com o `RestClient` |
| Angular servido por um nginx próprio, com o BFF atrás | Mais um container para entregar arquivos estáticos que o BFF já serve |
| Painel e links curtos no mesmo domínio | Rotas do painel disputariam o espaço do `/{code}`, e o cookie de sessão iria em todo clique |
| Estatísticas do usuário montadas na SPA | Até 100 requisições por página e o total calculado no navegador |
| NgRx para o estado | Cerimônia demais para poucas telas; signals e serviços resolvem |
| Angular com SSR | Painel autenticado, sem necessidade de SEO; complica o deploy |

## 4. Consequências

**Positivas**
- Nenhum token nem senha no navegador; o CSRF e a sessão seguem o padrão do Spring Security.
- A SPA não sabe quem é o provedor de identidade: trocar o `auth-server` pelo Keycloak (ADR-0003 D13) muda a
  configuração do BFF, não o Angular.
- Mesma origem: sem CORS, e o app só ganha o endpoint de estatísticas.
- `./mvnw verify` continua sendo o ponto único de build e teste.

**Negativas**
- Mais uma aplicação (o BFF) e mais um salto de rede em cada chamada.
- O painel depende do `auth-server`: o login real só funciona depois do ADR-0003 e da fase WA deste ADR.
- O refresh token do `encurtador-web` não rotaciona: um token vazado junto com o segredo do cliente vale até o fim dos
  30 dias ou até o logout.
- Um reinício do BFF pede login de novo (silencioso enquanto a sessão do `auth-server` valer).
- As telas de cadastro e de troca de senha são do `auth-server`: o visual precisa ser mantido coerente nos dois lados.
- O build do Maven passa a baixar o Node e os pacotes do npm.

## 5. Plano de implementação

Cada fase termina com `./mvnw verify` verde e o gate de cobertura do frontend atendido.

| Fase | Entrega | Depende de | Pronto quando |
|---|---|---|---|
| W0 | **Spike e esqueleto:** módulo `web` (Angular 22, `frontend-maven-plugin` com Node 26, ESLint com a regra das features, Vitest, tema do Material), módulo `bff` servindo a SPA com a CSP de D9, ignores do `.gitignore` e do `.dockerignore`, Node 26 no `.mise.toml` | — | A SPA vazia abre pelo BFF sem violação de CSP no console; imagem do BFF construída |
| W1 | Layout, navegação, foco e título por rota, mapa de erros e as páginas de links e estatísticas com a API simulada nos testes | W0 | Testes de componente verdes |
| W2 | Endpoint `GET /api/v1/stats` no `analytics` e `LinkLookup.ownedLinks` (D7), com testes de integração e o limite por usuário | — | `verify` do backend verde |
| WA | **`auth-server` para o painel:** cliente `encurtador-web` (confidencial, PKCE, refresh token sem rotação por 30 dias), endereços de retorno e de logout do painel, `email` no `id_token`, login automático ao fim do cadastro com volta só para endereços registrados, volta fixa da troca de senha, limite de `/oauth2/token` por `client_id` para clientes confidenciais (ADR-0003 D9 limita por IP, e todas as trocas do BFF sairiam do mesmo IP), tokens de cor e tipografia do painel no CSS das páginas, WCAG nas páginas | ADR-0003 A1 e A2 | Testes do `auth-server` verdes para cada item |
| W3 | BFF: registro do cliente em código, login com `returnTo`, cadastro, logout com revogação, sessão, CSRF, `/bff/session`, ponto de entrada 401, repasse de `/api/**`, limite do início do login, observabilidade | WA | Testes do BFF verdes; login real pela stack local |
| W4 | Integração da SPA com o BFF: guards, sessão expirada, troca de senha, erros reais | W1, W2, W3 | Fluxos manuais completos no ambiente local |
| W5 | Playwright com axe e teclado, serviço `bff` no compose, imagem e jobs do CI | W4 e ADR-0003 A3 | Job de ponta a ponta verde no CI |
| W6 | README, notas de implementação, marcações nos ADRs anteriores | W5 | — |

W0, W1 e W2 podem começar já, em paralelo à implementação do ADR-0003.

## 6. Riscos e questões em aberto

| # | Risco / questão | Mitigação |
|---|---|---|
| R1 | O `frontend-maven-plugin` com Node 26 não foi conferido em execução | Validar no W0, junto com o build da imagem |
| R2 | A lista de ids de link das estatísticas cresce com os links expirados | Aceito nesta escala; limite por usuário no endpoint (D7); se crescer, paginar ou arquivar links antigos |
| R3 | Sessões anônimas em memória criadas a cada início de login | Limite por IP no início do login (D4) e expiração de 2 horas |
| R4 | Visual das páginas do `auth-server` destoar do painel | Os tokens de cor e tipografia do painel ficam num CSS compartilhado, usado também pelo Thymeleaf do `auth-server` (WA) |
| R5 | Angular 23 a caminho (ainda sem pré-lançamento no npm) | `ng update` como parte da manutenção; nada no desenho depende de recurso experimental |

## 7. Impacto nos ADRs anteriores

| Decisão | Efeito |
|---|---|
| ADR-0001 §4 | A interface web deixa de estar fora do escopo |
| ADR-0001 RF02 | A renovação de sessão volta para o painel, pelo refresh token do `encurtador-web` |
| ADR-0001 D8 | O `analytics` ganha as estatísticas do usuário, só com links não removidos (D7) |
| ADR-0001 D11 | Entram os limites `user-stats` (por usuário) e o do início do login no BFF (por IP) |
| ADR-0001 D12 | Entra `GET /api/v1/stats` no contrato |
| ADR-0001 D13 | O BFF segue a mesma observabilidade |
| ADR-0001 D14 | Os cenários 8 e 12 (rotação e reuso de refresh token) continuam fora: o refresh token do `encurtador-web` não rotaciona; entram os testes do BFF e de ponta a ponta (D11) |
| ADR-0001 R8 | Não se aplica: sem rotação, renovações concorrentes não derrubam a sessão |
| ADR-0002 D1, D2 | Entram `web/` e `bff/` (→ `web`, `shared`, `ratelimit`); o `analytics` passa a depender do `ratelimit` |
| ADR-0002 D7 | O `coverage` inclui o `bff`; o frontend tem gate próprio (D11) |
| ADR-0002 D9 | O Dockerfile copia a pasta `web/` e usa cache do npm; o `bff` tem jar `exec`; `.mise.toml` fixa o Node 26 |
| ADR-0003 D3 | Volta o cliente `encurtador-web`: confidencial, código com PKCE e refresh token sem rotação por 30 dias |
| ADR-0003 D4 | Renovação por refresh token para o `encurtador-web`; logout com revogação e volta ao painel |
| ADR-0003 D5 | O `id_token` passa a levar o `email` |
| ADR-0003 D9 | Login automático ao fim do cadastro, voltas só para endereços registrados, volta fixa da troca de senha e limite de `/oauth2/token` por `client_id` para clientes confidenciais |
| ADR-0003 D11 | Serviço `bff` no compose |
| ADR-0003 R10 | A mitigação "sem refresh token" deixa de valer para o `encurtador-web`: os refresh tokens dele só servem com o segredo do cliente e são apagados pelo expurgo |
| ADR-0003 §5 | O trabalho do `auth-server` para o painel fica na fase WA deste ADR, depois de A1 e A2; o escopo de A1 não muda |

As marcações correspondentes foram feitas nos ADRs anteriores no aceite deste ADR (2026-11-04).

## 8. Registro de revisão

### Revisão 1 — 2026-11-04 (revisor de arquitetura independente)

O revisor leu o README e os ADRs 0001 a 0004 e conferiu o código, o código-fonte do Spring Security 7.1.2, os pacotes
do npm e as RFCs. Veredito: **aprovado com ressalvas** (2 bloqueantes, 9 importantes, 4 sugestões). As afirmações
sobre o repositório foram reconferidas. Todas as ressalvas foram aceitas:

| ID | Severidade | Tema | Decisão | Onde |
|---|---|---|---|---|
| R-01 | Bloqueante | Refresh token sem desenho: o SAS não revoga no reuso, e serializar renovações não funciona com várias instâncias | Aceito: refresh token sem rotação, com validade absoluta de 30 dias; a RFC 9700 só exige rotação para clientes públicos | D4, §3, §7 |
| R-02 | Bloqueante | Trabalho no `auth-server` sem fase própria | Aceito: fase WA, depois de ADR-0003 A1 e A2; W5 depende de A3 | §5, §7 |
| R-03 | Importante | `X-Forwarded-For` desnecessário e falsificável no `/{code}` | Aceito: o app não muda | D5 |
| R-04 | Importante | Limite de `/oauth2/token` por IP vira um balde só para o BFF | Aceito: por `client_id` para clientes confidenciais | §5 (WA), §7 |
| R-05 | Importante | Estatísticas do usuário contradiziam o tratamento de links removidos | Aceito: só links não removidos; `ownedLinks` especificado; limite por usuário | D7, §7 |
| R-06 | Importante | A CSP proposta bloquearia os estilos do Angular; `autoCsp` experimental | Aceito: política fechada em D9, com `'unsafe-inline'` só nos estilos | D3, D9, §3 |
| R-07 | Importante | `returnTo`, 401 em `/api/**` e logout não funcionam como escrito no Spring Security padrão | Aceito: resolvedor e tratador próprios, ponto de entrada 401, logout pelo `HttpClient` com navegação para a URL de logout | D4 |
| R-08 | Importante | Sessão no Redis exporia os tokens | Aceito: sessão em memória, com limite no início do login | D4, §3 |
| R-09 | Importante | Registro do cliente por `issuer-uri` não funciona no compose; PKCE precisa ser ligado | Aceito: registro em código, endereços público e interno separados, `requireProofKey(true)` | D4, D12 |
| R-10 | Importante | Build e imagem ficariam vermelhos (arquivos do `web`, jar `exec`, ignores, rotas da SPA, `/livez`) | Aceito | D12, §5 (W0) |
| R-11 | Importante | Cabeçalho e tabela de impacto incompletos; o ADR-0003 tirou o `encurtador-web`, não o reservou | Aceito | cabeçalho, §1.3, §7 |
| R-12 | Sugestão | Signal Forms já estáveis; sem pré-lançamento do Angular 23 | Aceito | D3, R5 |
| R-13 | Sugestão | Regra das features contraditória e sem ferramenta | Aceito: painel de estatísticas em `shared/`, `no-restricted-imports` | D2 |
| R-14 | Sugestão | CSRF para SPA e prefixo `__Host-` | Aceito: `csrf.spa()` e `__Host-` fora do local | D4 |
| R-15 | Sugestão | Acessibilidade das páginas do `auth-server`, foco por rota, teste de teclado, servidor de token falso, fuso recusado, cliques do dono | Aceito | D6, D10, D11 |

**Afirmações reconferidas pelo revisor:** Angular 22.2 com Node `^22.22.3 || ^24.15.0 || >=26.0.0`; Node 26 LTS;
Vitest e zoneless como padrão; Signal Forms estáveis no 22; `autoCsp` experimental; `csrf().spa()` no Spring Security
7; `OAuth2ClientHttpRequestInterceptor` renova o token, mas sem lock; `returnTo` não existe no `oauth2Login`; o SAS não
suporta `prompt=create`; Spring Cloud 2026.0 em RC; cookies de `localhost` não se separam por porta (RFC 6265).

### Notas de implementação — W0 a W6 e WA (2026-11-06)

Resultado: `./mvnw clean verify` verde com o `web` (lint, 74 testes no Vitest com 96,5% das linhas, acima do gate de
80%, e o build de produção) e o `bff` (46 testes, 96,8% das linhas) no gate agregado; o Playwright verde contra a stack do compose: cadastro → login → criar link pelo teclado →
estatísticas do link e do usuário → remover → trocar senha → sair → entrar com a senha nova, com o axe (WCAG 2.2 AA)
em cada página, inclusive as do `auth-server`, e sem violação de CSP. Respostas e desvios:

| Tema | Resultado |
|---|---|
| Node 26 e `frontend-maven-plugin` (R1) | Plugin 2.0.2 com o Node 26.13.0, instalado em `~/.m2/repository/.frontend/`, dentro do cache do CI e do Docker. Na imagem de build (Ubuntu noble), o Node 26 precisa da `libatomic1` |
| Scripts de instalação do npm | O npm 11 bloqueia os scripts de instalação e avisava a cada `npm ci`. Os cinco pacotes que os têm (`esbuild`, `lmdb`, `msgpackr-extract`, `@parcel/watcher`, `fsevents`) ficaram negados em `allowScripts`: o build não precisa deles, e nenhum script de terceiro roda no build |
| Saída do build (D2) | `angular.json` grava direto em `target/classes/META-INF/resources`, de onde o jar do `web` leva a SPA |
| CSP (D9) | Conferida no navegador. O `subresourceIntegrity` do Angular foi deixado desligado: ele põe um *import map* em linha no `index.html`, que a `script-src 'self'` bloquearia |
| Fontes (D3) | Fonte do sistema (`system-ui`): nenhum arquivo de fonte, nem de fora nem do BFF |
| Tokens visuais (R4) | Os mesmos valores em dois arquivos (`web/src/styles.scss` e `painel.css` do `auth-server`), e não um CSS compartilhado: o `auth-server` não depende do `web` |
| Signal Forms (D3) | O `matInput` do Material 22 reconhece o `[formField]`; os erros do app voltam como erros dos campos (`errors[]` → campo, `ALIAS_TAKEN` → alias) |
| CSRF (D4) | O `csrf.spa()` do Spring Security 7.1 adia o token e não grava o cookie sozinho: um filtro carrega o token em toda requisição. Na SPA, um extrator lê `__Host-XSRF-TOKEN` ou `XSRF-TOKEN`, porque o nome muda fora do local |
| Registro do cliente (D4) | Em código, com `issuerUri` público, `tokenUri` e `jwkSetUri` internos e `requireProofKey(true)`. Sem userinfo: o e-mail vem do `id_token`. Os tokens ficam no `HttpSessionOAuth2AuthorizedClientRepository`, na sessão, e não no serviço em memória padrão, que os guardaria fora dela |
| Troca de senha (D4) | `GET /bff/password` leva à página do provedor com a volta para `/conta`, como o `/bff/register`: a SPA não conhece endereços do provedor (`BFF_PASSWORD_CHANGE_URL`) |
| Login (D4) | Sem a página de login gerada pelo Spring Security (`loginPage("/")`); uma falha volta para `/?erro=login`, que a página inicial avisa |
| Sessão expirada (D4) | Refresh token recusado no repasse: a sessão do BFF acaba e a resposta é 401 `SESSION_EXPIRED` |
| Rotas da SPA (D6, D12) | Endereço desconhecido pedido pelo navegador recebe o `index.html` com 404, e a SPA mostra a página de não encontrado; chamadas da SPA recebem problem+json |
| Cache (D9) | Arquivos com hash no nome: `public, max-age=31536000, immutable`; `index.html` e rotas da SPA: `no-cache` |
| `RestClient` (D5) | O Spring Boot 4 só cria o `RestClient.Builder` com o starter próprio; o BFF monta os clientes com `RestClient.builder()` e timeouts de `bff.connect-timeout` e `bff.read-timeout` |
| Testes do BFF (D11) | Servidores HTTP do JDK fazem o papel do app e do `auth-server` (no lugar do `MockRestServiceServer` e do MockWebServer), o que cobre também os timeouts reais. A sessão logada é montada com o repositório real de clientes autorizados. O `csrf()` do spring-security-test troca o repositório de CSRF do filtro para o resto do contexto: os testes usam o cookie e o cabeçalho, como a SPA |
| Playwright (D11) | Em `web/e2e`, com `package.json` próprio, para o `npm ci` do build do Maven e da imagem não baixar o Playwright. Achou dois nomes acessíveis que não continham o texto visível (WCAG 2.5.3) e dois defeitos do `auth-server` (ADR-0003, notas: `auth_time` no login automático e logout depois da troca de senha) |
| CI (D12) | `verify` cobre o `web` e o `bff`; o job de imagem constrói as três; o job de ponta a ponta sobe a stack do compose, roda o script da CLI e o Playwright e guarda o relatório e os logs quando falha |

## Referências

- [RFC 9700 — Best Current Practice for OAuth 2.0 Security](https://datatracker.ietf.org/doc/html/rfc9700)
- [How-to: Authenticate using a Single Page Application with PKCE (documentação do SAS)](https://docs.spring.io/spring-authorization-server/reference/guides/how-to-pkce.html)
- [Angular — Security](https://angular.dev/best-practices/security)
- [Angular](https://angular.dev)
