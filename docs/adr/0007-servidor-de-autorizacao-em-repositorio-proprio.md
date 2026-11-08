# ADR-0007 — Servidor de autorização em repositório próprio

| Campo | Valor |
|---|---|
| Status | **Aceito** (2026-11-08) · **implementado** (2026-11-08; notas no §7) |
| Data | 2026-11-08 |
| Escopo | O `auth-server` sai deste repositório para [alcibarros-dev/auth](https://github.com/alcibarros-dev/auth) e chega ao encurtador como imagem Docker. O contrato com a API, o BFF e a CLI (emissor, JWKS, clientes, claims, páginas) não muda |
| Ajusta | ADR-0002 D3 e D4; ADR-0003 D2, D8, D11 e D12; ADR-0005 D1 |
| Decisores | Dono do projeto (repositório separado e referência por imagem no compose, decididos em 2026-11-08) · autor da proposta |

**Direcionadores.** O dono do projeto quer reaproveitar o servidor de autorização em outros projetos. Dentro deste
repositório, ele herda o `pom` pai do encurtador, os pacotes `br.com.encurtador`, os clientes OAuth e a audiência
escritos no `application.yml`, e só é distribuído junto com o resto.

---

## 1. Contexto

O `auth-server` já é uma aplicação separada (ADR-0003, D2): a API e o BFF falam com ele só por HTTP (emissor, JWKS,
`/oauth2/token`, revogação e páginas), e nenhum código do encurtador chama classes dele. O que o prende ao repositório:

| Dependência | Onde |
|---|---|
| `platform/shared` e `platform/ratelimit` | `pom.xml` do `auth-server` |
| Clientes `encurtador-cli` e `encurtador-web`, audiência `encurtador-api` | `application.yml` |
| Cliente `encurtador-dev`, segredo e chave de desenvolvimento | `application-local.yml` |
| Marca "Encurtador" nas páginas | `templates/layout.html` |
| Script de importação das contas antigas da API | `scripts/importar-usuarios.sql` |

## 2. Decisões

### D1 — Repositório `alcibarros-dev/auth`, sem histórico reescrito

O código vai para um repositório próprio, multi-módulo (`dev.alcibarros:auth-parent`, filho do
`spring-boot-starter-parent`), com o próprio `verify` e o mínimo de 80% de linhas somadas todas, num módulo `coverage`
como o `tools/coverage` daqui. O servidor fica no módulo `server` (`auth-server`, pacote `dev.alcibarros.auth.server`);
os outros módulos estão no D7. O `auth-server` tinha só dois commits aqui (`95cb8c2` e `967c85b`); o repositório novo
começa num commit só, que os cita, em vez de reescrever o histórico.

### D2 — Cópia do `shared` e do `ratelimit` usados

O servidor usa pouco da plataforma: o `RateLimiter` e o filtro, o `RedisGuard`, o `ProblemResponseWriter`, a validação
de tamanho em UTF-8, o `Clock` e o `@EnableScheduling`. Essas classes e os testes delas vão copiados para dentro do
`server`, sem as auto-configurações. Só as respostas de erro (`ProblemDetails`, `ProblemResponseWriter`), que o
`security` também usa, ficam num módulo próprio, `support` (`auth-support`), com auto-configuração. Publicar a
plataforma como biblioteca exigiria um registry Maven e um ciclo de versão para cada mudança dos dois lados; a cópia é
pequena e passa a evoluir com o servidor.

### D3 — Clientes, audiência e nome vêm de cada projeto

O `application.yml` da imagem não tem nenhum cliente. Cada projeto monta um arquivo em `/config/application.yml`
(`SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/config/`, na imagem) com:

- os clientes, em `spring.security.oauth2.authorizationserver.client.*`;
- a audiência dos access tokens, em `app.auth.audience` (obrigatória);
- o nome das páginas, em `app.auth.display-name`;
- as origens para onde o cadastro e a troca de senha voltam, em `app.auth.panel-origins` e `panel-default-url`;
- o CORS de `/oauth2/token`, em `app.cors.allowed-origins`.

O do encurtador é [`infra/auth-server/application.yml`](../../infra/auth-server/application.yml), com os mesmos
clientes de antes (`encurtador-cli`, `encurtador-web` e `encurtador-dev`) e endereços por variável de ambiente, com os
do compose local como padrão.

### D4 — Imagem `ghcr.io/alcibarros-dev/auth`, com versão fixada

O CI do repositório novo testa e publica a imagem no GitHub Container Registry, para `linux/amd64` e `linux/arm64`:
`:main` e `:sha-<commit>` a cada push na `main`, e `:X.Y.Z`, `:X.Y` e `:latest` a cada tag `vX.Y.Z`. O `compose.yaml`
do encurtador fixa a versão (`ghcr.io/alcibarros-dev/auth:${AUTH_IMAGE_TAG:-0.1.0}`); subir de versão é mudar esse
número, de propósito. O repositório e a imagem são públicos: o compose e o CI baixam sem login, e o `ghcr.io` não
cobra imagens públicas.

### D5 — No compose, o `auth-server` é infraestrutura

O serviço continua com o nome `auth-server` (a API e o BFF o chamam por `http://auth-server:8090`), mas sai do profile
`app`: sobe com o PostgreSQL e o Redis. Assim, o `spring-boot:run` da API e do BFF no perfil `local` o encontra no ar,
como faz com o banco. O segredo do `encurtador-web` no compose passa a ser o mesmo do perfil `local` do BFF
(`dev-web-secret`), para os dois jeitos de rodar o painel usarem o mesmo servidor.

Sai também tudo o que construía o servidor aqui: o módulo do `pom` raiz e do `tools/coverage`, a entrada da matriz de
imagens do CI e as Run Configurations `auth-server (local)`.

### D6 — O script de importação fica no encurtador

`importar-usuarios.sql` lê as tabelas antigas da API (`public.users` ou `public.accounts`): é operação do encurtador,
não do servidor. Ele volta para `infra/auth-server/`, e o [roteiro](../runbooks/migracao-auth-server.md) aponta para
lá. O teste dele (`ImportScriptTest`) precisava do schema `auth` do servidor e não veio junto; o script não muda.

### D7 — `security` e `security-api` também vão para o `auth`

> Acrescentada em 2026-11-08, a pedido do dono do projeto: tudo o que é segurança num lugar só, e os módulos de
> negócio só com regra de negócio.

Os dois módulos validam os tokens que o servidor emite, do lado da API (resource server), e passam a ser bibliotecas
do repositório `auth`, no pacote `dev.alcibarros.auth.security`:

| Módulo do `auth` | Artefato | Era aqui |
|---|---|---|
| `security-api/` | `auth-security-api` | `platform/security-api` |
| `security/` | `auth-security` | `platform/security` |

Para não depender do encurtador, o `security` passa a usar o `auth-support` nas respostas de erro, com o tipo
`urn:auth:problem:<código>` (os códigos não mudam: `UNAUTHENTICATED`, `INVALID_TOKEN`, `ACCESS_DENIED`,
`AUTH_UNAVAILABLE`), e o `auth-support` registra um `Clock` UTC quando a aplicação não tem um. Saem as anotações do
Spring Modulith: o encurtador as verifica só nos próprios pacotes.

Bibliotecas não viram imagem: a API as inclui no build, então elas chegam por um repositório Maven. O dono do projeto
escolheu o **GitHub Packages** (2026-11-08), ao lado da imagem, em vez do JitPack (serviço de terceiros, sem garantia
do Java 25) e do Maven Central (conta, namespace e assinatura GPG):

- O CI do `auth` publica o `auth-parent`, o `auth-support`, o `auth-security-api` e o `auth-security` a cada tag
  `vX.Y.Z`, com a versão da tag; o `auth-server` e o `auth-coverage` não são publicados.
- O encurtador apaga `platform/security` e `platform/security-api` e depende de `dev.alcibarros:auth-security` e
  `auth-security-api` na versão `auth.version` do `pom.xml` da raiz, a mesma da imagem do `auth-server`. Os imports
  passam de `br.com.encurtador.security` para `dev.alcibarros.auth.security`, e o `security` sai dos
  `allowedDependencies`: para o Spring Modulith, que só olha `br.com.encurtador`, é uma biblioteca externa.
- O GitHub Packages exige token até para pacotes públicos. O `.mvn/maven.config` liga o `.mvn/github-settings.xml`,
  que lê `GITHUB_ACTOR` e `GITHUB_TOKEN` do ambiente: o mesmo arquivo na máquina de cada um, no CI (o `GITHUB_TOKEN`
  do Actions, com `packages: read`) e no build das imagens, que recebe as duas como secrets do BuildKit.
- O pacote do `auth` precisa dar acesso de leitura ao repositório do encurtador (Package settings → Manage Actions
  access); sem isso, o `GITHUB_TOKEN` do CI daqui recebe 401.

## 3. Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Submódulo do Git com o código | Mistura de novo os dois repositórios: o build do encurtador volta a compilar o servidor, e cada clone precisa de `--recurse-submodules` |
| Publicar `shared` e `ratelimit` como bibliotecas | Registry Maven, versões e publicação a cada mudança, para as cerca de quinze classes usadas |
| Clientes por variável de ambiente | Um mapa de clientes em variáveis (`SPRING_SECURITY_OAUTH2_AUTHORIZATIONSERVER_CLIENT_...`) fica ilegível; o arquivo montado continua lendo variáveis onde faz sentido |
| Imagem privada | Exige `docker login` e token com `read:packages` em cada máquina e em cada CI que usa o servidor |

## 4. Consequências

- O encurtador deixa de compilar e testar o servidor: o `verify` daqui fica sem os testes dele, que rodam no CI do
  `auth`.
- Os testes de ponta a ponta (CLI e Playwright) continuam cobrindo a integração, agora com a imagem publicada.
- O CI do encurtador depende da imagem fixada existir no `ghcr.io`. Uma mudança que precise dos dois lados vira duas:
  publicar a versão nova do servidor e depois subir o número aqui.
- O painel e as páginas do servidor dividem os tokens de cor (ADR-0004, R4). Agora em repositórios diferentes, mudar
  um é lembrar do outro.

## 5. Critérios de pronto

1. O repositório `auth` passa no `./mvnw verify` sozinho, com o mínimo de cobertura.
2. A imagem `ghcr.io/alcibarros-dev/auth:0.1.0` sobe com o arquivo do encurtador e responde no emissor de antes.
3. Neste repositório, nenhum módulo, imagem, Run Configuration ou passo de CI constrói o servidor.
4. `./mvnw clean verify`, a stack do compose e os dois testes de ponta a ponta passam com a imagem.

## 6. Riscos e questões em aberto

| # | Risco | Mitigação |
|---|---|---|
| R1 | Antes da primeira publicação, o compose e o CI não acham a imagem | Ordem do primeiro push no §7; no local, a imagem pode ser construída do repositório `auth` com a mesma tag |
| R2 | O pacote no `ghcr.io` nasce privado | Conferir a visibilidade nas configurações do pacote depois da primeira publicação |
| R3 | Uma versão nova do servidor quebra o encurtador | A versão fica fixada; subir o número passa pelos testes de ponta a ponta do CI |

## 7. Notas de implementação

- Os testes do servidor usam clientes neutros (`app-cli`, `app-web`, `app-dev`, audiência `app-api`), definidos no
  `application-test.yml`; o perfil `local` do repositório `auth` traz clientes de exemplo (`demo-cli`, `demo-dev`) e
  um `compose.yaml` só com PostgreSQL (5434) e Redis (6380).
- O tipo dos erros `problem+json` do servidor passa de `urn:encurtador:problem:` para `urn:auth:problem:`.
- Repositório `auth` com 117 testes: 91 no `server`, 24 no `security` e 2 no `support`.
- **Primeira publicação:** push do repositório `auth`, tag `v0.1.0` (`git tag v0.1.0 && git push origin v0.1.0`),
  conferir que a imagem `auth` ficou pública, dar ao `encurtador-url` acesso de leitura aos pacotes Maven e só então
  o push do encurtador, cujo CI baixa a imagem e os jars.
- Antes da primeira publicação, os jars `0.1.0` foram instalados no `~/.m2` a partir de uma cópia do `auth` com a
  versão trocada, e a imagem foi construída localmente com a tag que o compose usa.
