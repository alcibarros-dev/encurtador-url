# ADR-0002 — Estrutura em módulos Maven

| Campo | Valor |
|---|---|
| Status | **Aceito** (2026-11-02), após a revisão 1 (§8) · implementado (M0 a M4, 2026-11-02; notas no §8) · D1, D2, D4, D5, D7, D8 e D9 ajustadas pelo ADR-0003 |
| Data | 2026-11-02 |
| Escopo | Divisão do build em módulos Maven, separação entre plataforma e negócio, contratos de segurança |
| Substitui | ADR-0001 D2 (estrutura física e regras de dependência). Ajusta ADR-0001 D9, D11, D14 e D15 |
| Relacionado | ADR-0003 (servidor de autorização), que parte desta estrutura |
| Decisores | Dono do projeto (aprovação) · autor da proposta · revisor de arquitetura independente (§8) |

**Direcionadores.** Fronteiras verificadas na compilação, e não só no teste; o módulo de segurança não pode depender
de nenhum outro módulo além do núcleo técnico; trocar o provedor de identidade (por exemplo, pelo Keycloak) tem de ser
possível sem reescrever o resto; preparar o repositório para uma segunda aplicação (o `auth-server` do ADR-0003).

**Vocabulário.** Neste documento, *app* é a aplicação HTTP do encurtador (rotas `/api/v1/**` e `/{code}`); *módulo* é
um módulo Maven; *módulo Modulith* é um pacote de primeiro nível verificado pelo Spring Modulith. Quando um módulo
Maven e um módulo Modulith coincidem, isso é dito explicitamente.

---

## 1. Contexto

O ADR-0001 (D2) organizou o código como monólito modular num único módulo Maven: um pacote por funcionalidade
(`identity`, `link`, `redirect`, `analytics`, `shared`), com as fronteiras verificadas pelo `ModularityTest` do Spring
Modulith. A opção multi-módulo foi considerada e descartada pelo custo.

Três coisas mudaram:

1. **Fronteira só no teste.** Um import proibido compila e só falha quando o `ModularityTest` roda. As dependências
   de bibliotecas também são globais: todo pacote enxerga Kafka, Redis, JPA e Spring Security.
2. **Segurança acoplada ao provedor.** O `shared/security` emite e valida tokens, conhece as rotas de autenticação do
   `identity` e assume que o `sub` do token é um número. Os controllers de `link`, `analytics` e `identity` recebem o
   `Jwt` e o convertem. Trocar o provedor de identidade exigiria mexer em todos eles.
3. **Segunda aplicação.** O ADR-0003 introduz um servidor de autorização próprio, que reaproveita parte da
   plataforma (formato de erro, disjuntor do Redis, rate limit). Com um módulo Maven só, isso não é possível sem
   copiar código.

### 1.1 Como os requisitos do dono do projeto são interpretados

| Requisito | Interpretação adotada |
|---|---|
| Centralizar a segurança num módulo | Todas as decisões de acesso **do app** ficam no `security`: validação do token, regras de rota, respostas 401 e 403, resolução do usuário autenticado. A posse de um recurso (um link só é visível ao dono) é um filtro de dados do módulo dono, como no ADR-0001 D9, e não sai dele. O `auth-server` é outra aplicação, com a própria configuração de segurança (ADR-0003) |
| O módulo de segurança não depende de outro módulo | O `security` depende só do `security-api` (o próprio contrato) e do `shared` (núcleo técnico, aceito pelo dono). Não depende de módulo de negócio, do rate limit nem do provedor de identidade |
| Trocar o provedor de identidade | Nenhum módulo, além do provedor, conhece o formato do token ou do `sub` (D3) |

## 2. Decisões

### D1 — Layout: plataforma, negócio e aplicações

> **Ajustada pelo ADR-0003** (2026-11-02): o pacote `identity` sai do `app`; entram `modules/account` e `auth-server/`.

```text
encurtador-url/
├── pom.xml                       agregador (packaging pom), herda spring-boot-starter-parent
├── platform/                     capacidades técnicas, sem regra de negócio
│   ├── shared/                   encurtador-url-shared        erros RFC 9457, tempo, UUIDv7, disjuntor do Redis
│   ├── ratelimit/                encurtador-url-ratelimit     janela fixa em Redis (Lua), filtro, regras
│   ├── security-api/             encurtador-url-security-api  contratos de segurança, sem framework
│   └── security/                 encurtador-url-security      validação de token, cadeia de filtros, 401/403
├── modules/                      negócio
│   ├── link/                     encurtador-url-link
│   ├── analytics/                encurtador-url-analytics
│   └── redirect/                 encurtador-url-redirect
├── app/                          encurtador-url-app           aplicação: classe principal, configuração,
│                                                              migrações, testes de integração e, TRANSITÓRIO,
│                                                              o pacote identity (sai no ADR-0003)
├── coverage/                     encurtador-url-coverage      sem código: gate de cobertura agregado
├── compose.yaml, Dockerfile, docs/, .github/ …
```

Regras do layout:

- Diretório curto, `artifactId` com o prefixo `encurtador-url-`. O pom raiz lista os caminhos
  (`<module>platform/shared</module>` …), sem poms intermediários em `platform/` e `modules/`.
- **Os pacotes Java não mudam de raiz:** `br.com.encurtador.<módulo>`. Cada módulo de `modules/` e `platform/` é
  também um módulo Modulith, com uma exceção: `security-api` e `security` formam um único módulo Modulith
  (`br.com.encurtador.security`). O `security-api` contém só o pacote raiz, que é a API pública do módulo Modulith;
  o `security` contém só subpacotes, que são internos. Não há pacote dividido entre jars.
- **O `identity` não vira módulo Maven.** Ele é o provedor de identidade de hoje e sai inteiro no ADR-0003; extraí-lo
  seria trabalho descartado. Até lá, fica como pacote dentro do `app`, ainda verificado pelo Modulith.
- O `app` contém também `EncurtadorApplication`, os `application*.yml`, as migrações Flyway (D8), os testes de
  integração (D6) e a configuração base do OpenAPI (título, versão, schema `Problem`). Nenhum módulo depende do `app`.

### D2 — Grafo de dependências

> **Ajustada pelo ADR-0003**: entram `account` (→ `security-api`, `shared`) e `auth-server` (→ `ratelimit`, `shared`).

| Módulo | Depende de |
|---|---|
| shared | — |
| security-api | — (só as anotações do Spring Modulith) |
| ratelimit | shared |
| security | security-api, shared |
| link | security-api, ratelimit, shared |
| analytics | link, security-api, shared |
| redirect | link, analytics, security-api, ratelimit, shared |
| app | todos os anteriores |
| coverage | todos os anteriores |

Regras:

1. **`security` depende só de `security-api` e `shared`**: não conhece módulo de negócio, rate limit nem provedor de
   identidade. É garantido pelo pom: o que não está declarado não compila.
2. **Módulos de negócio dependem de `security-api`, nunca de `security`.** Não recebem Nimbus nem a configuração da
   cadeia de filtros por transitividade.
3. **Só o `app` depende de implementações** (é a raiz de composição). Trocar uma implementação é trocar uma dependência
   do `app` e a sua configuração.
4. O `shared` é o núcleo técnico comum e continua sendo módulo Modulith aberto. Suas dependências de framework são
   opcionais (D11): quem usa uma parte do `shared` declara a biblioteca correspondente.
5. **O Spring Modulith continua.** O Maven barra imports entre módulos não declarados, mas não esconde subpacotes
   internos (`api/`, `infra/`…) nem impede o uso de dependências transitivas (quem depende de `analytics` compila
   contra `link`). O `allowedDependencies` dos `package-info.java` espelha a tabela acima e o `ModularityTest` segue
   no `app`.

### D3 — Contratos de segurança (`security-api`)

O `security-api` não tem dependências de framework. Contém:

| Tipo | Papel | Quem implementa ou usa |
|---|---|---|
| `@CurrentUser` | Anotação de parâmetro: `@CurrentUser long userId` nos controllers | `identity`, `link`, `analytics` usam; o `security` resolve |
| `UserIdResolver` | SPI: assunto do token (`sub` e claims) → id do usuário no app | O provedor de identidade implementa (hoje o `identity`; no ADR-0003, o `account`) |
| `PublicRoutes` | SPI: rotas públicas que cada módulo declara | `identity`, `redirect` |
| `OpenApiSecurity.BEARER` | Nome do esquema de segurança nos `@SecurityRequirement` | controllers protegidos |

Consequências diretas:

- Os módulos de negócio não veem `Jwt`. Hoje `CurrentUser.id(jwt)` faz `Long.parseLong(sub)`, o que só funciona com o
  provedor local. Com `UserIdResolver`, um `sub` em UUID (Keycloak, ADR-0003) não muda nenhum controller.
- A verificação de que o `sub` é numérico sai do validador de token do `security` e vai para o resolvedor do
  `identity`, que é quem sabe o formato.
- O `HostClassifier` continua no `link`: é regra de validade do destino (ADR-0001 D10), e não decisão de acesso. O
  próprio Javadoc registra que não há SSRF a evitar, porque o servidor nunca acessa o destino.

### D4 — Fronteira do `security`

> **Ajustada pelo ADR-0003** (D6): a validação do token passa a ser só por JWKS.

| Entra no `security` | Fica fora |
|---|---|
| Validação do token (assinatura, `iss`, `aud`, validade), com a origem da chave configurável: segredo HMAC enquanto o `identity` emitir tokens; chaves públicas (JWKS) a partir do ADR-0003 | Emissão de tokens, credenciais, login, cadastro, refresh: são do provedor de identidade |
| Cadeia de filtros: rotas públicas declaradas pelos módulos (`PublicRoutes`) mais as de infraestrutura (probes, Swagger no perfil `local`, `/error`); o resto exige token ou é `denyAll` | Posse dos recursos (filtro por `owner_id` no módulo dono) e regras de domínio motivadas por segurança (destino do link, política de senha) |
| Respostas 401 e 403 em ProblemDetail (ADR-0001 D9), e o repasse das exceções de segurança lançadas em controllers para elas | Formato de erro (`shared`) |
| Resolução do `@CurrentUser` | Rate limit (`ratelimit`), registrado pelo `app` (D5) |
| Esquema bearer do OpenAPI (`OpenApiCustomizer`) | Restante da documentação OpenAPI (`app`) |

### D5 — Rate limit como módulo de plataforma

> **Ajustada pelo ADR-0003** (D9): as regras do `identity` saem com ele; o `auth-server` registra o filtro com as
> próprias regras.

O rate limit sai de `shared/ratelimit` para `platform/ratelimit`, porque o `auth-server` (ADR-0003) também precisa
dele e não pode depender do `security`.

- O módulo contém o `RateLimiter` (Redis + Lua, protegido pelo `RedisGuard`), um filtro servlet, o modelo de regra
  (`RateLimitRule`: nome, método, padrão de rota, chave por IP ou por usuário) e a exceção 429.
- **Cada módulo declara as próprias regras** (beans `RateLimitRule`): o `identity` declara login, cadastro, refresh e
  troca de senha; o `link`, a criação de link; o `redirect`, o redirecionamento. O filtro aplica todas. Os limites
  ficam em `app.rate-limit.<regra>`, com as mesmas chaves de hoje, lidos como um mapa por nome de regra.
- **Quem registra o filtro é o `app`**, como filtro servlet ordenado depois da cadeia do Spring Security. A chave por
  usuário vem do usuário autenticado, resolvido pelo `UserIdResolver`. Assim o `security` não depende do `ratelimit`.
- Diferença em relação a hoje: uma requisição recusada pela cadeia de segurança (401 ou 403) não chega ao filtro e não
  conta no limite. As regras de hoje sobre rotas públicas (login, cadastro, refresh, redirecionamento) não mudam.
- O limite de login por e-mail continua chamado direto pelo serviço de login, via `RateLimiter`.

### D6 — Testes

- **Unitários ficam no módulo do código que testam.** O `@WebMvcTest` do `shared` ganha uma
  `@SpringBootConfiguration` de teste, porque a classe da aplicação fica no `app`. O caso que cobre o repasse das
  exceções de segurança vai para o `security`, junto com o código.
- **Testes de integração ficam no `app`**, nos mesmos pacotes de hoje (`br.com.encurtador.link…`). Eles sobem a
  aplicação inteira num contexto compartilhado e atravessam módulos; nenhum módulo pode depender do `app` sem ciclo.
- O `ModularityTest` fica no `app`, onde todas as classes estão no classpath.
- Consequência: `./mvnw -pl modules/link -am test` roda os unitários do `link` e dos módulos de que ele depende. A
  maior parte da cobertura vem dos testes de integração do `app`.

### D7 — Gate de cobertura no módulo `coverage`

> **Ajustada pelo ADR-0003**: o `coverage` inclui o `auth-server`, também com o jar `exec`.

O `jacoco:check` só mede as classes do próprio módulo, e os testes do `app` cobrem classes dos outros. O gate de
ADR-0001 D14 (≥ 80% de linhas fora de `config`, num único `jacoco.exec`) passa a ser aplicado no `coverage`:

1. o `app` gera o jar executável com o classificador `exec` (`<classifier>exec</classifier>` no `repackage`), e o
   artefato principal continua sendo o jar comum. Sem isso, o `coverage` receberia o jar executável, com
   `BOOT-INF/classes`, `BOOT-INF/lib` e o loader, e as exclusões deixariam de casar;
2. cada módulo com testes gera o seu `jacoco.exec` (agente via `prepare-agent`, herdado do pom raiz);
3. o `coverage`, último do reactor, descompacta as classes dos módulos no próprio `target/classes`
   (`dependency:unpack-dependencies` com `includeGroupIds=br.com.encurtador` e `includes=br/**`);
4. faz `jacoco:merge` de todos os `jacoco.exec`;
5. roda `jacoco:check` e `jacoco:report` sobre o exec unificado, com as mesmas exclusões de hoje.

O relatório passa a ficar em `coverage/target/site/jacoco/`.

### D8 — Migrações Flyway no `app`

> **Ajustada pelo ADR-0003** (D8): `users` vira `accounts`, do módulo `account`; o `auth-server` tem histórico Flyway
> próprio no schema `auth`.

As migrações continuam num único histórico, em `app/src/main/resources/db/migration`, com uma sequência global de
versões. O dono de cada tabela fica registrado aqui, e não pela localização do arquivo:

| Tabela | Dono |
|---|---|
| `users`, `refresh_tokens` | `identity` (transitório; o ADR-0003 redistribui) |
| `links` | `link` |
| `click_events` | `analytics` |

Motivos: a `V1__identity.sql` não pode ser dividida (migração aplicada não é editada, ADR-0001 D6); remover um módulo
não pode apagar migrações já aplicadas do histórico; e o Flyway não precisa varrer jars aninhados no jar executável.

### D9 — Build, execução e imagem

> **Ajustada pelo ADR-0003** (D11): o Dockerfile constrói as duas aplicações, e as Run Configurations passam a cobrir
> as duas, com uma composta que sobe ambas.

- **Pom raiz:** versões de terceiros (as de hoje), `dependencyManagement` dos módulos internos com
  `${project.version}`, configuração comum de plugins (Surefire com o agente do Mockito, JaCoCo `prepare-agent`,
  `dependency:properties`) e o perfil Maven `local`, que continua como o dono do projeto o definiu.
- **Empacotamento:** só o `app` declara o `spring-boot-maven-plugin` (`repackage` com o classificador `exec`, D7, e
  `build-info`).
- **`spring-boot:run` na raiz:** o pom raiz define `spring-boot.run.skip=true` e o `app` o redefine como `false`. O
  diretório de trabalho do `run` é a raiz do projeto (`${project.basedir}/..` no pom do `app`), senão o suporte a
  Docker Compose não acha o `compose.yaml`.
- **Run Configurations do IntelliJ:** a Maven (`encurtador-url (local)`) não muda. A de debug, do tipo Application,
  passa a apontar para o módulo `encurtador-url-app`.
- **Dockerfile:** um só, parametrizado por `ARG MODULE=app`, com as portas do `EXPOSE` e do `HEALTHCHECK` também
  por `ARG`. Copia o pom raiz e os poms dos módulos antes das fontes (camada de dependências) e pega o
  `${MODULE}/target/*-exec.jar`. O `.dockerignore` passa a ignorar `**/target/`.
- **CI:** `./mvnw -B verify` na raiz; o artefato de cobertura passa a vir de `coverage/target/site/jacoco/`.

### D10 — Ajustes de código

A divisão em si só move arquivos (`git mv`, preservando o histórico). Os contratos de D3 a D5 e D11 exigem estes
ajustes, todos sem mudança de comportamento (rotas, JSON, códigos de erro, propriedades, variáveis de ambiente e banco
permanecem iguais, com a exceção registrada em D5):

1. `shared.security` vira `br.com.encurtador.security` (raiz no `security-api`, subpacotes no `security`);
   `shared.ratelimit` vira `br.com.encurtador.ratelimit`.
2. `ApiException.headers()`, vazio por padrão: a exceção de rate limit o sobrescreve com `Retry-After`, e o handler
   global do `shared` deixa de conhecê-la (hoje ele faz `instanceof RateLimitExceededException`, o que criaria o ciclo
   `shared` ↔ `ratelimit`).
3. O repasse de `AuthenticationException` e `AccessDeniedException` sai do handler global do `shared` e vai para um
   advice do `security` com precedência maior. O `shared` deixa de depender do Spring Security.
4. `AppProperties` se divide: `app.base-url` fica no `shared`; `app.jwt.*` passa a ser lido pelo `security`
   (validação) e pelo `identity` (emissão). As chaves não mudam.
5. A emissão de token (`AccessTokenIssuer`, `JwtEncoder`) vai para o `identity`; o `security` só valida.
6. `@CurrentUser` e `UserIdResolver` substituem `@AuthenticationPrincipal Jwt` + `CurrentUser.id(jwt)` nos
   controllers; o `identity` fornece o resolvedor local.
7. `PublicRoutes` e `RateLimitRule` por módulo substituem as listas fixas do `SecurityConfig` e do `RateLimitFilter`;
   o filtro de rate limit sai da cadeia do Spring Security e passa a ser registrado pelo `app` (D5).
8. O `OpenApiConfig` se divide: o esquema bearer vai para o `security`, e o resto (título, versão, schema `Problem`)
   vai para o `app`.
9. O `PageResponse` vai para o `link`, o único que o usa. O 401 de "conta deste token não existe mais", também usado
   só pelo `link`, vai para o `shared` (`error/`).
10. As configurações dos módulos de plataforma passam a ser auto-configurações (D11).

### D11 — Módulos de plataforma como auto-configurações

Os módulos de `platform/` seguem o padrão dos starters do Spring Boot:

- Os beans de cada módulo são declarados em classes `@AutoConfiguration`, listadas em
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. O `app` continua fazendo
  component scan em `br.com.encurtador`, e o `@SpringBootApplication` já exclui do scan as classes listadas como
  auto-configuração: os beans não são registrados duas vezes. Uma aplicação com outro pacote raiz (o `auth-server`,
  em `br.com.encurtador.auth`) recebe os beans de plataforma sem precisar escanear `br.com.encurtador`.
- As dependências de framework do `shared` (Spring Data Redis e Lettuce para o `RedisGuard`, por exemplo) são
  `optional`, e a auto-configuração correspondente usa `@ConditionalOnClass`. Quem usa o `RedisGuard` declara o Redis.
- O handler de problem+json do `shared` vale só para `@RestController`. Em aplicações com páginas (o `auth-server`), as
  páginas tratam os próprios erros.

## 3. Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Manter um módulo Maven (ADR-0001 D2) | As fronteiras continuam só no teste, e o `auth-server` do ADR-0003 teria de copiar código |
| Estrutura plana (todos os módulos na raiz) | Mistura plataforma e negócio; o agrupamento torna visível o que pode ser reaproveitado por outra aplicação |
| Login, cadastro e tokens dentro do `security` (`security-api` + `security-local`) | O provedor viraria parte do `security`, e a troca pelo Keycloak exigiria reescrevê-lo; as credenciais teriam de sair da tabela `users` só para evitar a dependência do `identity` |
| `security` dependendo do `identity` para verificar credenciais | Viola o requisito: o `security` não pode depender de outro módulo |
| Rate limit registrado pelo `security`, dentro da cadeia | Cria a dependência `security` → `ratelimit`, que vai além do `shared` aceito pelo dono |
| Extrair o `identity` como módulo Maven | Trabalho descartado no ADR-0003, que remove o `identity` |
| Mover o `HostClassifier` para o `security-api` | É validação de domínio do destino, não decisão de acesso, e não protege contra SSRF |
| Módulo `platform/redis` para o `RedisGuard` | Mais um módulo; dependências opcionais com `@ConditionalOnClass`, como faz o próprio Spring Boot, resolvem o mesmo problema |
| Testes de integração por módulo (`@ApplicationModuleTest`) | Vários contextos e containers, build mais lento, e os fluxos ponta a ponta continuariam no `app` |
| Gate de cobertura por módulo | Só enxergaria os testes unitários |
| Migrações no módulo dono da tabela | `V1` mistura tabelas de dois donos; remover um módulo removeria migrações aplicadas; risco do Flyway em jars aninhados |
| JPMS (`module-info.java`) para esconder internos na compilação | Atrito com reflexão do Spring e do Hibernate (`opens` por pacote); o Modulith já cobre os internos |

## 4. Consequências

**Positivas**
- Import proibido entre módulos não compila; cada módulo declara as bibliotecas que usa.
- O `security` e os módulos de negócio não conhecem o provedor de identidade. Trocá-lo é trocar a implementação de
  `UserIdResolver`, as regras do provedor e a origem das chaves.
- Um módulo pode ser compilado e testado isoladamente.
- A plataforma (`shared`, `ratelimit`) pode ser usada por outra aplicação do repositório, sem component scan.

**Negativas**
- 10 poms e um módulo `coverage` que só existe para o gate.
- Testes de integração longe do código que testam.
- A regra de dependências existe em dois lugares (poms e `package-info.java`) e precisa ficar em sincronia; o
  `ModularityTest` acusa divergência.
- O `security` ainda conhece rotas de infraestrutura (probes, Swagger, `/error`), mas não as de negócio.
- O `identity` fica dentro do `app` até o ADR-0003.
- Requisições recusadas com 401 ou 403 deixam de contar no rate limit (D5).

## 5. Plano de implementação

Cada fase termina com `./mvnw verify` verde, pelo menos 377 testes, nenhum removido sem substituto, e cobertura de
linhas fora de `config` de pelo menos 97%.

| Fase | Entrega | Pronto quando |
|---|---|---|
| M0 | Raiz vira agregador; todo o código vai para `app/`; classificador `exec`; cria `coverage`; ajusta Dockerfile, `.dockerignore`, CI, `spring-boot:run` e a Run Configuration de debug | `verify` com o gate no `coverage`; `./mvnw spring-boot:run` sobe o compose; a imagem fica `healthy` |
| M1 | Ajustes de código de D10, ainda dentro do `app`: pacotes `security` e `ratelimit`, contratos, regras por módulo, filtro de rate limit fora da cadeia | `verify` e `ModularityTest` com as regras novas |
| M2 | Extrai `platform/` (`shared`, `ratelimit`, `security-api`, `security`) com as auto-configurações (D11) | `./mvnw -pl platform/security -am verify` passa só com o que o `security` declara |
| M3 | Extrai `modules/` (`link`, `analytics`, `redirect`) | `verify`; `ModularityTest` |
| M4 | README e notas de implementação neste ADR | — |

M0 separa a mudança de build da mudança de código; M1 concentra toda a mudança de código num build ainda de um módulo.

## 6. Riscos e questões em aberto

| # | Risco / questão | Mitigação |
|---|---|---|
| R1 | O gate agregado não reproduzir os 97,8% | Validar na M0, antes de qualquer extração; diferença indica exec ou classe faltando |
| R2 | Beans de plataforma registrados duas vezes (scan e auto-configuração) | Configurações só como `@AutoConfiguration` (D11); teste de contexto no `app` |
| R3 | `@WebMvcTest` no `shared` sem a classe da aplicação | Configuração de teste própria (D6); validar na M2 |
| R4 | `spring-boot:run` na raiz executar o `run` nos módulos sem `main` | `spring-boot.run.skip` (D9); validar na M0 |
| R5 | Regra de dependências divergir entre poms e `package-info.java` | `ModularityTest` no `verify` |
| R6 | `dependency:go-offline` tentar baixar os módulos irmãos | A versão 3.10.0 já exclui o reactor por padrão; o Dockerfile precisa copiar todos os poms |

## 7. Impacto no ADR-0001

| Decisão | Efeito |
|---|---|
| D2 | **Substituída por este ADR** quanto à estrutura física e às dependências. Continuam valendo: pacote raiz como API pública, `shared` aberto, nenhum módulo lê tabelas de outro, invalidação do cache pelo `redirect`, `Clock` injetado, serviços sem `SecurityContextHolder` |
| D9 | A tabela de autorização das rotas continua valendo, mas as rotas públicas passam a ser declaradas pelos módulos (`PublicRoutes`); o `sub` passa a ser traduzido pelo `UserIdResolver` |
| D11 | Os limites continuam os mesmos; as regras passam a ser declaradas pelos módulos, e o filtro sai da cadeia do Spring Security (D5) |
| D14 | O gate passa a ser medido no `coverage` (D7); os testes de integração ficam no `app` (D6) |
| D15 | Dockerfile parametrizado por módulo e portas, jar `exec` e caminho novo do relatório de cobertura no CI (D9) |

As marcações correspondentes foram feitas no ADR-0001 no aceite deste ADR (2026-11-02).

## 8. Registro de revisão

### Revisão 1 — 2026-11-02 (revisor de arquitetura independente)

A revisão cobriu este ADR e o ADR-0003 juntos. Veredito: **aprovado com ressalvas** (3 bloqueantes, 9 importantes, 8
sugestões no total). As afirmações do revisor sobre o repositório foram reconferidas. Achados que tocam este ADR:

| ID | Severidade | Tema | Decisão | Onde |
|---|---|---|---|---|
| R-01 | Bloqueante | O `coverage` receberia o jar executável do `repackage` | Aceito: classificador `exec`, unpack só de `br/**`, Dockerfile com `*-exec.jar` | D7, D9 |
| R-07 | Importante | O `shared` arrastava Spring Security, Lettuce, springdoc e Spring Data para todos; plataforma dependia de component scan | Aceito com outra solução para o Redis: dependências opcionais com `@ConditionalOnClass` em vez de mover o `RedisGuard` | D10, D11 |
| R-09 | Importante | Requisitos 2 e 3 sem interpretação escrita; `security` → `ratelimit` ia além do `shared` aceito | Aceito: interpretação escrita; filtro de rate limit registrado pelo `app` | §1.1, D2, D4, D5 |
| R-11 | Importante | `EXPOSE` e `HEALTHCHECK` fixos no Dockerfile | Aceito | D9 |
| R-12 | Importante | Cabeçalho sem D9 e D11; tabela de impacto incompleta | Aceito | cabeçalho, §7 |
| R-13 | Sugestão | `HostClassifier` não é decisão de acesso nem proteção contra SSRF | Aceito: fica no `link` | D3, §3 |
| R-14 | Sugestão | Run Configuration de debug aponta para o módulo antigo; perfil Maven `local` herdado | Aceito em parte: a Run Configuration muda; o perfil Maven continua, por decisão do dono do projeto, e funciona herdado | D9 |
| R-19 | Sugestão | "377 testes" não se sustenta; `-am` também roda os testes das dependências | Aceito | §5, D6 |
| R-20 | Sugestão | Extrair o `identity` é trabalho que o ADR-0003 descarta | Aceito: o `identity` fica no `app` | D1, §5 |

### Notas de implementação — M0 a M4 (2026-11-02)

Resultado: `./mvnw clean verify` com 387 testes (35 no `shared`, 27 no `ratelimit`, 6 no `security`, 155 no `link`, 20
no `analytics`, 10 no `redirect` e 134 no `app`) e 97,8% de linhas fora de config no gate agregado (1187 de 1214, as
mesmas 26 linhas sem cobertura de antes, mais uma). Achados e desvios:

| Tema | Resultado |
|---|---|
| Gate agregado (R1) | Reproduziu na M0 exatamente o número de antes (1167/1193). O relatório HTML sai do `report-aggregate`, com o código-fonte de cada módulo, em `coverage/target/site/jacoco-aggregate/`, e não em `coverage/target/site/jacoco/` como dizia o D7. O gate usa o exec unificado sobre as classes descompactadas. Entrou a exclusão `*AutoConfiguration` |
| Segredo JWT | O D10 previa que emissão e validação lessem `app.jwt.secret` cada uma por conta própria. No compose, sem `APP_JWT_SECRET`, a propriedade é `${random.value}…`, e cada leitura daria um segredo diferente: todo token seria recusado. O `app` lê o segredo uma vez (`JwtSigningKeyConfig`) e expõe a chave como bean `SecretKey`, usado pela emissão (`identity`) e pela validação (`security`). Conferido no compose com o segredo aleatório. O `HmacSecret` foi para `shared/crypto` |
| Auto-configurações (D11) | Cada módulo de plataforma tem uma auto-configuração que escaneia o próprio pacote, em vez de redeclarar os beans com `@Bean`. No `app`, que já escaneia `br.com.encurtador`, a segunda varredura reencontra as mesmas classes e não as registra de novo. Um teste com `WebApplicationContextRunner` confirma que o `shared` funciona sem o scan da aplicação |
| Handler de problem+json | **Não** foi restrito a `@RestController`, como dizia o D11: o 404 de rota inexistente vem do handler de recursos estáticos, que não é controller, e perderia o problem+json. Como desligá-lo nas páginas do `auth-server` fica para o ADR-0003 |
| Pacote `br.com.encurtador.app` | A configuração que junta módulos que não se conhecem (registro do filtro de rate limit, chave JWT, OpenAPI base) ficou num pacote próprio do `app`, verificado pelo Modulith como módulo `App` |
| Rate limit por usuário | A chave por usuário é o `sub` do token (`getUserPrincipal()`), e não o id resolvido: o filtro não depende do `security`. No provedor local os dois valores são iguais |
| `@CurrentUser` no OpenAPI | O springdoc ignora a anotação: o parâmetro não aparece na documentação, como acontecia com `@AuthenticationPrincipal` |
| Exceções de segurança no MVC | O repasse ao Spring Security saiu do handler do `shared` para um advice do `security` com precedência maior. O caso de teste foi junto, e o `security` ganhou testes do resolvedor de `@CurrentUser` |
| Dockerfile (R6) | `COPY --parents` copia os poms e as pastas `src/` de todos os módulos; o `dependency:go-offline` funcionou sem baixar módulos irmãos. Imagem conferida no compose |
| `spring-boot:run` (R4) | Na raiz, sobe só o `app` no perfil `local`, com o compose encontrado; conferido na M0 e no fim |
| `build-info` | O `/actuator/info` passa a mostrar o artefato `encurtador-url-app` |
