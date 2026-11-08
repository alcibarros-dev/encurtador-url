# ADR-0006 — Redirecionamento como serviço próprio

| Campo | Valor |
|---|---|
| Status | **Aceito** (2026-11-07) · **implementado** (2026-11-07; notas no §8) |
| Data | 2026-11-07 |
| Escopo | O caminho `GET\|HEAD /{code}` sai da API e vira a aplicação `apps/redirect`, com imagem, porta e serviço do compose próprios. O contrato público do redirecionamento (302, 404, 410, 429, `Cache-Control`, métrica `shortener.redirect`) não muda |
| Ajusta | ADR-0001 D4, D7, D8, D11, D13 e D14; ADR-0002 D1, D2 e D9; ADR-0005 D1 |
| Decisores | Dono do projeto (porta 8060 e leitura direta no PostgreSQL, decididas em 2026-11-07) · autor da proposta |

**Direcionadores.** O redirecionamento é o caminho quente: quase todo o tráfego, o p99 abaixo de 50 ms (RNF01) e a
exigência de continuar no ar sem Redis e sem Kafka (RNF02). O resto da API (links, estatísticas, contas) recebe uma
fração disso. Para atender mais cliques, hoje é preciso subir a API inteira de novo, com o consumidor de cliques, o
pool do JPA e as rotas autenticadas que ninguém está usando a mais. O dono do projeto quer escalar o redirecionamento
sozinho.

---

## 1. Contexto

O módulo `redirect` (ADR-0002) já é uma fronteira no código, mas roda dentro da API e depende de três vizinhos dela:

| Dependência | Para quê | Por que prende o redirect à API |
|---|---|---|
| `link` (`LinkLookup`) | Ler o link no PostgreSQL quando o cache não tem a entrada | Traz o JPA, as entidades e o `LinkController` |
| `analytics` (`ClickRecorder`) | Entregar o clique à fila em memória que publica no Kafka | O mesmo módulo tem o consumidor do Kafka e as rotas de estatística |
| Evento `LinkDeleted`, em processo | Gravar o tombstone de remoção depois do commit (`LinkCacheInvalidator`) | Só funciona no mesmo processo que remove o link |

Além disso, a rota pública `/*` e o limite `redirect` são declarados para a cadeia do Spring Security da API.

## 2. Decisões

### D1 — `apps/redirect`, porta 8060

Uma aplicação nova, com o mesmo Dockerfile das outras (`MODULE=apps/redirect`):

| O quê | Valor |
|---|---|
| Pasta e artefato | `apps/redirect`, `encurtador-url-redirect`. O código do redirecionamento sai de `modules/redirect` e vive na própria aplicação, como no `auth-server`: nenhuma outra aplicação o usa (D3) |
| Classe principal | `br.com.encurtador.RedirectApplication`, que escaneia `br.com.encurtador` como a API |
| Portas | 8060 (redirecionamento, `/livez`, `/readyz`) e 8061 (actuator completo, só na rede interna) |
| Imagem e serviço do compose | `encurtador-redirect`, serviço `redirect` no profile `app` |
| `spring.application.name` | `encurtador-redirect` |
| Readiness | `readinessState` e `db`, como na API: Redis e Kafka ficam fora (RNF02) |

A API continua em 8080. O `shortUrl` que ela devolve passa a apontar para o redirect: `app.base-url` da API é
`http://localhost:8060` no padrão e no compose. É a única mudança que o usuário vê: os links curtos passam de
`http://localhost:8080/{code}` para `http://localhost:8060/{code}`. Na API, `GET /{code}` deixa de existir e cai na
regra geral (401 sem token).

Escalar é subir mais instâncias do serviço `redirect` atrás de um balanceador: ele não guarda estado em memória além
da fila de cliques, que é por instância e já tolera perda (ADR-0001, D8).

### D2 — Leitura direta e só de leitura no PostgreSQL

No miss do cache, o redirect lê a tabela `links` por JDBC (`select id, target_url, status, expires_at from links where
code = ?`), sem JPA. O schema e as migrações continuam da API: o redirect não roda Flyway.

- O pool sobe só de leitura: `spring.datasource.hikari.read-only: true` e a propriedade `readOnlyMode=always` do
  driver. Sem ela, o driver ignora o read-only nas conexões em autocommit, que são todas as do redirect. Com as duas,
  o PostgreSQL recusa qualquer escrita, por engano ou não; um teste confere.
- O redirect não depende de a API estar no ar, e a leitura pode ir para uma réplica trocando só a URL do datasource.
- O acoplamento ao schema é explícito e pequeno: quatro colunas de uma tabela. Mudança nelas passa a exigir migração
  compatível com as duas aplicações (expandir antes, contrair depois), registrada no §6.
- No compose, o redirect usa o mesmo usuário do banco que a API. Um usuário próprio, só com `SELECT` em `links`, fica
  para quando houver ambiente fora do local (§6, R2).

### D3 — Contratos compartilhados em dois módulos pequenos

O código do redirecionamento (controller, resolução, leitura no banco, fila de cliques) vai inteiro para
`apps/redirect`, e `modules/redirect` deixa de existir: em `modules/` só fica o que mais de uma aplicação usa. O que a
API e o redirect precisam combinar vai para dois módulos sem regra de negócio, que as duas aplicações usam:

| Módulo | Pacote | Conteúdo | Quem usa |
|---|---|---|---|
| `modules/link-cache` | `br.com.encurtador.linkcache` | `LinkCache`, `CachedLink`, `LinkCacheProperties` (`app.cache.*`) e o `RedisLinkCache`, com a chave `link:v1:{code}` e o formato JSON do ADR-0001 D7 | O redirect lê e preenche; a API grava o tombstone de remoção |
| `modules/clicks` | `br.com.encurtador.clicks` | O evento `LinkClicked` (versão 1, a mesma do tópico `link-clicks`) | O redirect publica; o analytics consome |

O formato no Redis e no Kafka não muda: instâncias de versões diferentes leem o que as outras gravam.

### D4 — A API grava o tombstone; o redirect só lê

O `LinkCacheInvalidator` vai para o pacote `br.com.encurtador.app` da API, que já é "o que junta módulos que não se
conhecem". Ele continua sendo um `@TransactionalEventListener(AFTER_COMMIT)` sobre `LinkDeleted` e grava o mesmo
`SET` incondicional no Redis compartilhado. Nada da semântica do ADR-0001 D7 muda: o preenchimento continua `SET NX`
no redirect, a defasagem máxima com o Redis fora continua `app.cache.max-ttl`, e remoção desfeita não toca o cache.

Publicar a remoção no Kafka para o redirect invalidar foi descartado (§3): o Redis já é compartilhado, e o Kafka
acrescentaria um caminho a mais para a invalidação falhar.

### D5 — O produtor de cliques vai com o redirect

A fila em memória (`ClickRecorder`), o `ClickSender` e o health indicator `kafkaPublish` saem do `analytics` e entram
em `apps/redirect`, com a configuração própria `app.clicks.*` (`buffer-capacity` e `topic`). O `analytics` fica só
com o consumidor, a DLT, a criação dos tópicos e as estatísticas. O tópico continua criado pela API, dona do
consumidor; se o redirect publicar antes, o broker do compose cria o tópico com as partições padrão (3, as mesmas).

### D6 — Rota, limite e segurança no redirect

- Sem Spring Security: o redirect não tem rota autenticada. Os erros continuam em `application/problem+json`, pelo
  handler do `shared`.
- O limite `redirect` (600 por minuto por IP) passa a valer para todo `GET` e `HEAD` do serviço, exceto as probes, e
  não só para os de um segmento. Sem o firewall do Spring Security, variações de caminho (`//code`, `;param`) poderiam
  escapar de uma regra por padrão; contar tudo fecha essa porta e não muda nada para quem usa o link.
- O filtro de rate limit é registrado pela própria aplicação, como na API e no auth-server.
- A rota sai da documentação OpenAPI da API, que deixa de ter a tag "Redirecionamento".

### D7 — Testes

- Os testes do caminho quente vão para `apps/redirect`, com o PostgreSQL, o Redis (atrás do Toxiproxy) e o Kafka do
  Testcontainers: redirecionamento e cache, Redis fora e pendurado, Kafka pausado, subida sem brokers, rate limit e
  publicação do clique.
- O schema dos testes do redirect vem das migrações da API, lidas da pasta (`filesystem:../api/...`) pelo Flyway, só
  no escopo de teste: o teste roda contra o schema real, e a aplicação continua sem Flyway.
- A API mantém os testes do consumidor (o clique chega ao tópico e aparece nas estatísticas), do tombstone de remoção
  e do formato no Redis.
- A ligação entre os dois serviços (link criado na API, redirecionado no redirect, clique nas estatísticas) é coberta
  pelos dois testes de ponta a ponta sobre o compose.

## 3. Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Escalar a API inteira | É o que o dono do projeto quer evitar: cada réplica leva consumidor, JPA e rotas autenticadas |
| Redirect chamando a API por HTTP no miss | O redirect cairia junto com a API, e cada miss viraria uma chamada a mais. Recusada pelo dono do projeto |
| Redirect assumindo a porta 8080 | Mudaria a API de porta e, com ela, BFF, Swagger, CORS do auth-server, CLI e README. Recusada pelo dono do projeto |
| Invalidação pelo Kafka | O Redis já é compartilhado; o Kafka seria mais um ponto de falha entre a remoção e o 410 |
| Redirect com o módulo `link` inteiro | Traria o JPA e o `LinkController`, e a API de links apareceria no serviço de redirect |
| Um módulo de contrato só | `LinkCache` e `LinkClicked` não têm nada em comum; separados, cada aplicação leva só o que usa |

## 4. Consequências

**Positivas**
- O redirect escala sozinho, sobe mais rápido e com menos memória: sem JPA, sem consumidor, sem Spring Security.
- Uma queda da API não derruba o redirecionamento, e uma carga de cliques não disputa threads com o painel.
- A fronteira do caminho quente fica física: o que ele usa está na lista de dependências do pom.

**Negativas**
- Um serviço a mais para subir, observar e versionar; a stack local passa de três para quatro aplicações.
- Duas aplicações leem a tabela `links`: o schema deixa de ser privado da API.
- Os links curtos mudam de porta no ambiente local (8060).
- A remoção depende do Redis compartilhado para chegar ao redirect antes do TTL, como já dependia.

## 5. Critérios de pronto

1. `./mvnw clean verify` verde, com o gate de cobertura cumprido.
2. As quatro imagens são construídas, e a stack sobe pelo compose com o `redirect` saudável.
3. Os dois testes de ponta a ponta passam, com o link curto em `http://localhost:8060`.
4. Na API, `GET /{code}` responde 401, e o Swagger não lista mais a rota.
5. O redirect continua respondendo com a API parada.

## 6. Riscos e questões em aberto

| # | Risco / questão | Mitigação |
|---|---|---|
| R1 | Uma migração da API muda uma coluna que o redirect lê | Migração em duas etapas (expandir, contrair); o teste do redirect roda contra as migrações reais e quebra no `verify` |
| R2 | O redirect usa o mesmo usuário do banco que a API | Conexões só de leitura (D2); usuário próprio com `SELECT` em `links` quando houver ambiente fora do local |
| R3 | Versões diferentes de API e redirect no ar ao mesmo tempo | Formatos no Redis (`link:v1`) e no Kafka (`v: 1`) inalterados e versionados |
| R4 | O tópico ainda não existe quando o redirect publica | O broker cria com as partições padrão; a API declara o tópico na subida |

## 7. Impacto nos ADRs anteriores

| Decisão | Efeito |
|---|---|
| ADR-0001 D4 | `GET\|HEAD /{code}` é atendido pelo serviço `redirect`, na porta 8060 |
| ADR-0001 D7 | O cache é preenchido pelo redirect e invalidado pela API, no mesmo Redis e no mesmo formato |
| ADR-0001 D8 | O produtor de cliques fica no redirect (`app.clicks.*`); o consumidor, na API |
| ADR-0001 D11 | O limite `redirect` é aplicado pelo serviço `redirect` a todo `GET` e `HEAD` |
| ADR-0001 D13 | O health `kafkaPublish` e o histograma de `shortener.redirect` passam para o redirect |
| ADR-0001 D14 | Cenários 2 a 6, 10, 14 (clique), 15 e 16 (redirect) rodam em `apps/redirect` |
| ADR-0002 D1 e D2 | Módulos novos `link-cache` e `clicks`; `modules/redirect` sai, e o código vai para `apps/redirect`, que depende só de `link-cache`, `clicks`, `ratelimit` e `shared` |
| ADR-0002 D9 | Quarta imagem, `encurtador-redirect` |
| ADR-0005 D1 | `apps/` ganha `redirect/` (porta 8060); `modules/` perde `redirect/` e ganha `clicks/` e `link-cache/` |

## 8. Notas de implementação

Implementado em 2026-11-07, num commit só: as mudanças de pasta e de dependência precisam andar juntas para o build
funcionar.

- **Só leitura de verdade.** O teste que tenta um `UPDATE` pelo pool do redirect falhou na primeira versão: com
  `hikari.read-only: true` sozinho, o driver do PostgreSQL ignora o read-only nas conexões em autocommit (o
  `readOnlyMode` padrão dele é `transaction`). Entrou `readOnlyMode=always` nas propriedades do datasource (D2).
- **Um lugar só.** A primeira versão manteve o código em `modules/redirect`, com `apps/redirect` só juntando o
  módulo, como na API; a aplicação precisava do artefato `encurtador-url-redirect-app`. Como nenhuma outra aplicação
  usa o redirecionamento, o dono do projeto pediu o código dentro de `apps/redirect`, no modelo do `auth-server`, e o
  módulo foi apagado antes do commit. Pacotes e classes não mudaram. A classe principal fica em `br.com.encurtador`,
  com nome próprio (`RedirectApplication`), para não colidir com a `EncurtadorApplication` da API no relatório
  agregado do JaCoCo.
- **Padrão da rota.** O redirect não depende do módulo `link`; a rota usa uma cópia do padrão do `ShortCode`, e o
  `CodePatternTest` confere as duas lendo o código-fonte do `ShortCode`. Uma dependência de teste no `link` traria o
  JPA para o contexto dos testes de integração, o que quebrou o build quando o código veio para `apps/redirect`.
- **Filtro de rate limit.** Registrado pelo próprio serviço (`RedirectRoutesConfig`), já que o serviço não
  tem cadeia de segurança para ele vir depois.
- **API mais enxuta.** `LinkLookup.findByCode`, `LinkLookup.CODE_PATTERN` e `LinkRepository.findByCode` só eram usados
  pelo redirect e saíram. O `LinkSnapshot` continua, para as estatísticas.
- **Testes.** Os do caminho quente foram para `apps/redirect` com os mesmos cenários; os links de teste são gravados
  por uma conexão própria, com escrita (`TestLinks`), e o Flyway roda as migrações da API por outra, porque o pool da
  aplicação recusa escrita. Na API ficaram o consumidor (os eventos entram direto no tópico, no formato que o redirect
  publica), o tombstone de remoção (`LinkCacheInvalidationIntegrationTest`, inclusive com o Redis fora) e o formato no
  Redis. A API passou a responder 401 em `GET /{code}`, conferido no `SecurityIntegrationTest` e no teste da CLI.
- **Mapa dos componentes.** O `docs/componentes.html` ainda mostra o redirecionamento dentro da API: ele estava sendo
  reescrito em outra sessão quando este ADR foi implementado, e o nó novo entra lá.
