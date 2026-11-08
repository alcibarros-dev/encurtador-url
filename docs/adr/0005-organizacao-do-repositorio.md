# ADR-0005 — Organização do repositório por papel

| Campo | Valor |
|---|---|
| Status | **Aceito** (2026-11-06), após a revisão 1 (§8) · **implementado** (O0 a O2, 2026-11-06; notas de implementação no §9; o critério 8 do D9 depende do primeiro run do CI) · D1 ajustada pelos ADRs 0006 e 0007 |
| Data | 2026-11-06 |
| Escopo | Pastas da raiz do repositório: onde ficam as aplicações, o frontend, os testes de ponta a ponta, as ferramentas de build e os arquivos de ambiente. Nenhum comportamento da aplicação muda |
| Ajusta | ADR-0002 D1, D2, D7, D8 e D9; ADR-0003 D2, D8, D11 e D12; ADR-0004 D2, D11 e D12 |
| Decisores | Dono do projeto (aprovação) · autor da proposta · revisor de arquitetura independente (§8) |

**Direcionadores.** Quem abre o repositório, no GitHub ou na IDE, entende pela raiz o que cada pasta é: o que roda
como processo, onde está a regra de negócio, onde está o painel, como testar tudo junto. Os comandos do dia a dia
(`./mvnw verify`, `docker compose up`) continuam funcionando sem parâmetro novo. Nenhuma regra de dependência, nenhum
pacote Java e nenhum comportamento da aplicação muda.

---

## 1. Contexto

O ADR-0002 dividiu o build em módulos e agrupou as bibliotecas em `platform/` e `modules/`. Os ADRs 0003 e 0004
acrescentaram, cada um na raiz, o que precisavam. Hoje a raiz tem dez pastas de quatro naturezas diferentes, no mesmo
nível e sem nada no nome que diga o que são:

| Pasta | O que é |
|---|---|
| `app/`, `auth-server/`, `bff/` | Aplicações: cada uma vira um processo e uma imagem |
| `web/` | O painel em Angular, que não roda sozinho: vai dentro do jar do `bff`. Guarda também o Playwright (`web/e2e`), que testa a stack inteira |
| `platform/`, `modules/` | Bibliotecas técnicas e de negócio (já agrupadas) |
| `coverage/` | Um pom sem código: o gate de cobertura agregado |
| `scripts/` | Um só script, o teste ponta a ponta da CLI |
| `docker/` | Só a configuração do pgAdmin |
| `docs/` | ADRs e roteiros |

Problemas que isso causa na leitura:

- As aplicações estão soltas, e as bibliotecas, agrupadas: não há um lugar que responda "o que roda?".
- `app` não diz o que a aplicação faz. Os outros nomes dizem (`auth-server`, `bff`).
- Os testes de ponta a ponta ficam em dois lugares (`scripts/` e `web/e2e`), e um deles dentro do frontend, embora
  teste também o `auth-server` e a API.
- `coverage/` e `docker/` parecem partes do sistema, mas são ferramenta de build e configuração de ambiente.

## 2. Decisões

### D1 — Layout por papel

> **Ajustada pelo ADR-0006** (2026-11-07): `apps/` ganha `redirect/` (porta 8060, com o código que era de
> `modules/redirect`); `modules/` perde `redirect/` e ganha `clicks/` e `link-cache/`.
>
> **Ajustada pelo ADR-0007** (2026-11-08): `apps/` perde `auth-server/`, que vai para o repositório
> [auth](https://github.com/alcibarros-dev/auth) e chega como imagem; `infra/` ganha `auth-server/`,
> com a configuração do encurtador para ela e o script de importação das contas.

```text
encurtador-url/
├── apps/                 o que roda como processo e vira imagem
│   ├── api/              (hoje app/) API do encurtador e redirecionamento, porta 8080
│   ├── auth-server/      servidor de autorização, porta 8090
│   └── bff/              BFF do painel, porta 8070
├── frontend/
│   └── web/              (hoje web/) SPA Angular, empacotada no jar do bff
├── modules/              negócio: account, link, analytics, redirect            (sem mudança)
├── platform/             técnico: shared, ratelimit, security-api, security      (sem mudança)
├── e2e/                  testes da stack inteira, sobre o compose
│   ├── cli/              (hoje scripts/) ponta-a-ponta.sh
│   └── playwright/       (hoje web/e2e) o painel no navegador, com o axe
├── tools/
│   └── coverage/         (hoje coverage/) só o gate de cobertura agregado
├── infra/
│   └── pgadmin/          (hoje docker/pgadmin) servidores pré-cadastrados do pgAdmin
├── docs/                 adr/ e runbooks/                                        (sem mudança)
├── compose.yaml, Dockerfile, .dockerignore, .env.example
└── pom.xml, mvnw, mvnw.cmd, .mvn/, .mise.toml, README.md
```

A raiz passa de dez pastas soltas para oito, cada uma com um papel no nome. Os nomes das pastas seguem o inglês, como
`platform/` e `modules/` já seguem; o conteúdo dos documentos e os nomes de scripts continuam em português.
`frontend/`, `tools/` e `infra/` começam com uma subpasta cada: o custo está no §3.

### D2 — `app` vira `apps/api`

**Muda**

| O quê | Hoje | Depois |
|---|---|---|
| Pasta | `app/` | `apps/api/` |
| Artefato Maven | `encurtador-url-app` | `encurtador-url-api` (também no `dependencyManagement` do pom raiz e no pom do `coverage`) |
| Jar executável | `encurtador-url-app-<versão>-exec.jar` | `encurtador-url-api-<versão>-exec.jar` |
| `/actuator/info` | `build.artifact` e `build.name` `encurtador-url-app` | `encurtador-url-api` |
| `spring.application.name` (o `service.name` dos logs ECS) | `encurtador-url` | `encurtador-api`, como as outras duas aplicações, cujo nome já é o da imagem |
| Serviço do compose | `app`, em `http://app:8080` | `api`, em `http://api:8080`, com `args: MODULE: apps/api` declarado como nos outros dois |
| Imagem | `encurtador-url` | `encurtador-api`, simétrica a `encurtador-auth-server` e `encurtador-bff` |
| Run Configurations | `encurtador-url (local)` e `encurtador-url (local, debug)` | `api (local)` e `api (local, debug)`; o `stack (local)`, que as cita pelo nome, muda no mesmo commit |
| Termo nos textos novos | "o app" | "a API" (README e este ADR); os ADRs anteriores mantêm o vocabulário com que foram aceitos |

**Não muda, e por quê**

| O quê | Motivo |
|---|---|
| Profile `app` do compose | Passa a significar "as aplicações": `docker compose --profile app up` sobe as três, e o comando que já está em toda a documentação não muda |
| Pacote `br.com.encurtador.app`, módulo `App` do Spring Modulith e `EncurtadorApplication` | É o módulo de composição; renomear mexeria nas regras de dependência (`allowedDependencies`) e em `import`, sem ganho na leitura das pastas |
| Prefixo de configuração `app.*` e as variáveis `APP_*` | São o prefixo das propriedades próprias das três aplicações, e não o nome do módulo |
| Usuário `app` (uid 10001) e `WORKDIR /app` da imagem | São da imagem genérica do Dockerfile, a mesma para as três aplicações |
| Prefixo `app` dos componentes Angular | É o prefixo dos seletores (`app-root`), convenção do Angular |
| Demais artefatos | `encurtador-url-auth-server`, `encurtador-url-bff`, `encurtador-url-web` e `encurtador-url-coverage` mantêm o nome |

### D3 — O painel em `frontend/web`

O módulo `web` muda só de lugar: mesmo artefato, mesmo projeto Angular, mesma saída em `META-INF/resources`. O
Playwright sai dele (D4), e o `web` fica só com a SPA e os testes de unidade e de componente. A saída de cobertura do
Vitest passa a `frontend/web/coverage/` (D5).

### D4 — Testes de ponta a ponta em `e2e/`

- `scripts/ponta-a-ponta.sh` vai para `e2e/cli/ponta-a-ponta.sh`; `scripts/` deixa de existir.
- `web/e2e` vai para `e2e/playwright`, com o mesmo `package.json` próprio. Os comandos do README passam a
  `npm --prefix e2e/playwright …`, ainda rodando da raiz.
- Em `web/e2e`, o Playwright herdava o `.editorconfig` e o `.prettierrc` do `web`. Em `e2e/playwright` ele ganha cópias
  dos dois. Na raiz, eles valeriam também para o Java, que segue outra indentação.
- O script de importação das contas continua em `apps/auth-server/scripts/`: é operação do `auth-server`, não teste.

### D5 — Ferramentas de build em `tools/`

- O módulo `coverage` vai para `tools/coverage`. O relatório passa a sair em
  `tools/coverage/target/site/jacoco-aggregate/`.
- A pasta não se chama `build/` (§3): muitos `.gitignore` globais e IDEs tratam esse nome como saída de build.
- **`.gitignore`.** Hoje há `coverage/` e uma exceção `!/coverage/`, que só libera a pasta da raiz. Em
  `tools/coverage`, qualquer arquivo novo ficaria ignorado sem aviso. As duas linhas dão lugar a
  `/frontend/web/coverage/`, a única saída de cobertura que precisa ser ignorada.
- **`.dockerignore`.** Ignora o mesmo caminho exato, `frontend/web/coverage/`. Um `**/coverage/` tiraria o pom do
  `tools/coverage` do contexto da imagem e quebraria o reactor.

### D6 — Arquivos de ambiente

- `docker/pgadmin` vai para `infra/pgadmin`, e o volume do pgAdmin no `compose.yaml` passa a
  `./infra/pgadmin/servers.json`. Sem essa troca, o Docker criaria uma pasta vazia no caminho antigo, e o pgAdmin
  subiria sem o servidor pré-cadastrado, sem que nenhum teste percebesse (D9).
- `compose.yaml`, `Dockerfile`, `.dockerignore` e `.env.example` ficam na raiz. Ali:
  - `docker compose up` e `docker build .` funcionam sem `-f`;
  - o compose lê o `.env` sozinho;
  - o suporte a Docker Compose do Spring Boot encontra o `compose.yaml` no diretório de trabalho das aplicações (D7).

  `.secrets/` também fica na raiz, montada pelo compose. A linha `docker/` do `.dockerignore` passa a `infra/`.

### D7 — Build Maven e imagem

- **Módulos.** O pom raiz lista os módulos pelos caminhos novos: `apps/api` antes de `apps/auth-server` e
  `apps/bff`, para o `spring-boot:run` sem `-pl` continuar subindo a API, a primeira aplicação do reactor.
- **Sem poms intermediários** em `apps/`, `frontend/` ou `tools/`, como já não há em `platform/` e `modules/`: a IDE
  agrupa pela pasta, e um pom agregador a mais seria só mais um arquivo.
- **`relativePath`.** Cada módulo movido declara `<relativePath>../../pom.xml</relativePath>`, como os de `platform/`
  e `modules/`.
- **Diretórios a partir da raiz.** O diretório de trabalho do `spring-boot:run` e o diretório onde o `coverage`
  procura os `jacoco.exec` passam a usar `${maven.multiModuleProjectDirectory}`, a raiz do projeto, que o `mvnw` e a
  IDE definem. Assim deixam de depender da profundidade da pasta: a próxima mudança de nível não quebra nada, e no
  `coverage` a quebra seria silenciosa (D9). O `workingDirectory` fica uma vez só, no `pluginManagement` do
  `spring-boot-maven-plugin` no pom raiz, e as três aplicações o herdam.
- **Para rodar uma aplicação:** `./mvnw -pl apps/auth-server -am spring-boot:run`, e assim por diante.
- **Dockerfile.** O `MODULE` passa a ser o caminho do módulo (`--build-arg MODULE=apps/bff`), com padrão `apps/api`.
  Os `COPY --parents */*/pom.xml` e `*/*/src` já cobrem todos os módulos, em dois níveis; os padrões de um nível
  (`*/pom.xml`, `*/src`) deixam de achar arquivos, o que o BuildKit aceita, e saem. Só a cópia do painel muda
  (`COPY frontend/web/ frontend/web/`).
- **Compose.** Os três serviços declaram `MODULE` com o caminho novo (`apps/api`, `apps/auth-server`, `apps/bff`).

### D8 — IDE, CI e documentação

- **Run Configurations:**
  - Os comandos passam a `-pl apps/api -am`, `-pl apps/auth-server -am` e `-pl apps/bff -am`, com os nomes de D2.
  - A de debug da API aponta para o módulo `encurtador-url-api`. Hoje ela aponta para `encurtador-url (1)`, um nome
    antigo que a IDE local ainda usa (`.idea/compiler.xml`).
- **CI:**
  - caminhos novos no `MODULE` da matriz de imagens, no relatório de cobertura, no script da CLI e no Playwright,
    incluindo o `cache-dependency-path`;
  - a matriz ganha uma chave `name` só para exibição (`api`, `auth-server`, `bff`), para os checks não se chamarem
    "Imagem Docker (apps/api)";
  - o artefato `jacoco-report`, que todo `verify` gera, passa de `if-no-files-found: ignore` para `error`: um caminho
    errado falha o job, em vez de passar sem relatório. Os artefatos dos passos de falha (relatório do Playwright e
    logs do compose) continuam com `ignore`, porque podem faltar quando a falha vem antes deles.
- **Ignorados:** `.gitignore` e `.dockerignore` mudam como em D5 e D6, e `web/e2e/` vira `e2e/` no `.dockerignore`.
- **Texto e comentários.** A lista não é fechada: muda tudo o que as buscas do D9 encontrarem fora de `docs/adr/`. O
  revisor achou, entre outros:
  - **README:** árvore, comandos, caminhos, a tabela de variáveis e a de portas, e "o app" virando "a API" (D2).
  - **Roteiro de migração:** o serviço `app` dos comandos, o caminho do script de importação e o do teste da CLI.
  - **Comentários** no Dockerfile, no pom raiz, no `compose.yaml` e no `ponta-a-ponta.sh`; no `importar-usuarios.sql`;
    no `.dockerignore`; nos cabeçalhos dos `application-local.yml`; e no `styles.scss`.
- **ADRs anteriores:** o texto deles continua como foi aceito, com uma marcação "ajustada pelo ADR-0005" nas decisões
  afetadas (§7), como nos aceites anteriores.
- **Transição local** (README): antes de atualizar, `docker compose --profile app down` ainda com o compose antigo.
  Senão, o container `app` continua segurando `127.0.0.1:8080`, e o serviço `api` não sobe. Depois, a IDE reimporta o
  projeto Maven.

### D9 — Execução e critérios de pronto

**Linha de base, antes de mover qualquer pasta:**
- os testes por módulo;
- as linhas cobertas sobre o total no gate agregado (hoje 1510 de 1555);
- o número de linhas "Loading execution data file" no log do `coverage`;
- a lista de arquivos (`jar tf`) dos três `*-exec.jar`.

**Execução.** Toda mudança de pasta é feita com `git mv`. Com ele, o Git rastreia o arquivo pelo caminho novo antes de
qualquer regra de ignorado valer (D5). O Git não grava a renomeação: ele a deduz depois, por semelhança do conteúdo.
Por isso nenhum arquivo movido muda além do necessário, e os poms, que mudam pouco, continuam reconhecidos como
renomeados.

**Pronto quando:**

1. `./mvnw clean verify` está verde e repete a linha de base: os mesmos testes por módulo, as mesmas linhas
   cobertas sobre o total e o mesmo número de "Loading execution data file". `tools/coverage/target/jacoco-merged.exec`
   existe, e o log mostra "All coverage checks have been met". Sem o exec agregado, o `jacoco:check` só avisa e pula o
   gate, e o relatório HTML continuaria mostrando a cobertura de cada módulo.
2. `git check-ignore -v tools/coverage/pom.xml` não imprime nada.
3. `git diff --cached -M --name-status` só mostra renomeações (`R`) e as modificações esperadas (`M`). Um par `D` +
   `A` aponta um arquivo que perdeu o histórico.
4. O `jar tf` dos três `*-exec.jar` é igual ao da linha de base, exceto `META-INF/maven/**` e `build-info.properties`.
5. As três imagens são construídas (`docker compose --profile app build`), e a stack sobe pelo compose.
6. O pgAdmin abre com o servidor `encurtador-url` já cadastrado.
7. Os dois testes de ponta a ponta passam.
8. No primeiro run do CI, o artefato `jacoco-report` existe e os checks aparecem com os nomes curtos.
9. As duas buscas abaixo não acham nada fora de `docs/adr/`.

Caminhos antigos no começo de um caminho: a classe de caracteres deixa de fora `src/app/`, `./app/`, `../app/`,
`apps/auth-server/` e `frontend/web/`. O único falso positivo esperado é o `# syntax=docker/dockerfile:1` do
Dockerfile.

```bash
git grep -n -E '(^|[^A-Za-z0-9_./-])(app|web|coverage|scripts|docker|auth-server|bff)/' -- ':!docs/adr' ':!**/package-lock.json'
```

Nomes antigos: o `-pl`, o `MODULE` e a matriz do CI com o nome curto, o serviço e a imagem da API, e os comandos
do compose com o serviço `app`. O fim de palavra é a classe `([^A-Za-z0-9_-]|$)`, porque o `git grep` do macOS não
aceita `\b`:

```bash
E='([^A-Za-z0-9_-]|$)'; git grep -n -E -e "-pl +(app|auth-server|bff)$E" -e "MODULE[=:] *(app|auth-server|bff)$E" -e "module: *(app|auth-server|bff)$E" -e 'http://app:' -e 'encurtador-url-app' -e 'encurtador-url:' -e "(build|up -d|up -d --build|logs|stop|restart) +app$E" -- ':!docs/adr'
```

## 3. Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Deixar como está | O problema de leitura continua e cresce a cada aplicação nova |
| Pom agregador por grupo (`apps/pom.xml`, `frontend/pom.xml`) | Mais arquivos e um nível a mais de herança para entender; a IDE já agrupa os módulos pela pasta, e `platform/` e `modules/` funcionam sem isso |
| `compose.yaml` e `Dockerfile` em `infra/` | Todo comando passaria a precisar de `-f infra/compose.yaml`, e as três aplicações, de `spring.docker.compose.file` no perfil local; o `.env` deixaria de ser lido sozinho |
| `build/coverage` | `build/` é a pasta de saída do Gradle e de várias IDEs, e costuma estar em `.gitignore` globais: o módulo poderia sumir do commit sem aviso |
| `web/` na raiz, movendo só o Playwright (revisão 1, R-12) | Cada pasta da raiz passaria a ser um grupo, menos esta. Com `frontend/`, a raiz se lê toda por papel, e há lugar para outro frontend. O custo, aceito pelo dono do projeto: `frontend/`, `tools/` e `infra/` começam com uma subpasta cada, e os caminhos do dia a dia ficam um nível mais longos (`npm --prefix frontend/web start`, `frontend/web/src/app/…`) |
| Painel em `apps/web` | O `web` não roda sozinho: ele vai dentro do jar do `bff`. Junto das aplicações, sugeriria um processo que não existe |
| `frontend/painel` | Misturaria português nos nomes de pasta, que seguem o inglês |
| Caminhos relativos (`${project.basedir}/../..`) | Repetidos em quatro poms e presos à profundidade; no `coverage`, um erro desliga o gate sem aviso (D9) |
| Renomear os pacotes Java para `api` | Mexe nas regras do Spring Modulith (`allowedDependencies`) e em todo `import`, sem ganho na leitura das pastas |
| Manter o nome `app` (`apps/app`) | Repetitivo, e continua sem dizer o que a aplicação faz |
| Repositório separado para o frontend | Perde o `./mvnw verify` único, as mudanças atômicas entre BFF e SPA e o teste de ponta a ponta no mesmo CI |
| Um README por grupo de pastas | Repetiria o README da raiz, que já descreve cada módulo; dois lugares para manter em dia |

## 4. Consequências

**Positivas**
- A raiz se lê por papel: aplicações, painel, negócio, plataforma, testes da stack, ferramentas, ambiente e docs.
- Os testes de ponta a ponta ficam num lugar só, fora do frontend.
- Pasta, artefato, nome nos logs, serviço do compose, imagem e Run Configurations da API passam a ter o mesmo nome.
- Os diretórios de build passam a vir da raiz do projeto, e não da profundidade de cada pasta.

**Negativas**
- Uma mudança grande em número de arquivos, embora mecânica: mudam de caminho todos os de `app/`, `auth-server/`,
  `bff/`, `web/` e `coverage/`.
- Os textos dos ADRs anteriores citam os caminhos e o vocabulário antigos; quem os lê depende da marcação do §7.
- Comandos que o dono do projeto já decorou mudam (`-pl app` vira `-pl apps/api`), e os caminhos do painel ficam um
  nível mais longos.
- Mudam nomes visíveis fora do código: o artefato no `/actuator/info`, o `service.name` dos logs da API, o nome DNS do
  serviço no compose, a imagem e os nomes dos checks do CI.
- Ambiente local: a stack precisa ser derrubada com o compose antigo antes da atualização, e a IDE precisa reimportar
  o projeto Maven.

## 5. Plano de implementação

As fases terminam num commit só: os caminhos precisam mudar juntos para o build funcionar.

| Fase | Entrega | Pronto quando |
|---|---|---|
| O0 | Linha de base (D9) e `docker compose --profile app down` com o compose antigo | Números e listas registrados |
| O1 | `git mv` das pastas (D1 a D6) e as mudanças de D7 e D8 no build: poms (módulos, `relativePath`, artefato da API, `dependencyManagement`, `coverage`, `${maven.multiModuleProjectDirectory}`), Dockerfile, `compose.yaml`, CI, `.gitignore`, `.dockerignore`, Run Configurations e cópias do `.editorconfig` e do `.prettierrc` | Critérios 1 a 5 do D9 |
| O2 | O texto e os comentários de D8 e as marcações nos ADRs 0002 a 0004; stack no compose | Critérios 6, 7 e 9 do D9; o 8 depois do push |

## 6. Riscos e questões em aberto

| # | Risco / questão | Mitigação |
|---|---|---|
| R1 | A IDE guarda os módulos pelos nomes antigos (a local chama a API de `encurtador-url (1)`) e pode regravar as Run Configurations versionadas com eles | Reimportar o projeto Maven no IntelliJ; conferir que a configuração de debug acha o módulo `encurtador-url-api` e que o `git status` não mostra os XML de `.idea/runConfigurations` regravados |
| R2 | Uma referência ao caminho ou ao nome antigo esquecida num lugar que o `verify` não exercita (CI, compose, documentação) | As duas buscas do D9, o build das imagens, o pgAdmin e os dois testes de ponta a ponta antes do commit |
| R3 | O container `app` do compose segura a porta 8080 e fica órfão com o serviço renomeado para `api` | `docker compose --profile app down` com o compose antigo, antes de atualizar (O0, README); os volumes (banco, Kafka, pgAdmin) não dependem do nome do serviço |
| R4 | Artefatos `encurtador-url-app` antigos no `~/.m2` e imagem `encurtador-url` antiga | Inofensivos: nada mais os usa; podem ser apagados à mão |
| R5 | O Git deduz a renomeação por semelhança: um arquivo movido e muito alterado no mesmo commit aparece como removido e criado, e o `git log --follow` perde o histórico dele | Nenhum arquivo movido muda além dos caminhos; a conferência é o `git diff --cached -M --name-status` do D9 |
| R6 | Se a `main` exigir checks pelo nome, os nomes novos da matriz ficam pendentes | Conferir a proteção da `main` no GitHub depois do primeiro run (critério 8 do D9) |
| R7 | O Maven 4 troca `maven.multiModuleProjectDirectory` por `session.rootDirectory` | O wrapper fixa o Maven 3.9; a troca entra na migração para o Maven 4 |

## 7. Impacto nos ADRs anteriores

| Decisão | Efeito |
|---|---|
| ADR-0002 D1 | O layout ganha `apps/`, `frontend/`, `e2e/`, `tools/` e `infra/`; `app/` vira `apps/api/`; nos textos novos, "o app" passa a ser "a API" |
| ADR-0002 D2 | O artefato `encurtador-url-app` vira `encurtador-url-api`; as regras de dependência não mudam |
| ADR-0002 D7 | O módulo `coverage` fica em `tools/coverage`, procura os `jacoco.exec` a partir de `${maven.multiModuleProjectDirectory}`, e o relatório sai em `tools/coverage/target/site/jacoco-aggregate/` |
| ADR-0002 D8 | As migrações ficam em `apps/api/src/main/resources/db/migration` |
| ADR-0002 D9 | `-pl apps/api -am`; o `MODULE` do Dockerfile passa a ser o caminho do módulo (padrão `apps/api`); a imagem da API se chama `encurtador-api`; o diretório de trabalho do `spring-boot:run` é `${maven.multiModuleProjectDirectory}`, declarado uma vez no pom raiz |
| ADR-0003 D2 | O `auth-server` fica em `apps/auth-server` |
| ADR-0003 D8 | O script de importação fica em `apps/auth-server/scripts/importar-usuarios.sql` |
| ADR-0003 D11 | O serviço `app` do compose vira `api`; caminhos novos no CI |
| ADR-0003 D12 | O teste ponta a ponta da CLI fica em `e2e/cli/ponta-a-ponta.sh` |
| ADR-0004 D2 | O `web` fica em `frontend/web`, e o `bff`, em `apps/bff` |
| ADR-0004 D11 | O Playwright fica em `e2e/playwright` |
| ADR-0004 D12 | O Dockerfile copia `frontend/web/`; o BFF chama a API em `http://api:8080` no compose |

As marcações correspondentes foram feitas nos ADRs anteriores no aceite deste ADR (2026-11-06).

## 8. Registro de revisão

### Revisão 1 — 2026-11-06 (revisor de arquitetura independente)

O revisor leu o README e os ADRs 0001 a 0005 e conferiu as afirmações contra o código: poms, Dockerfile,
`.dockerignore`, `compose.yaml`, CI, `.gitignore`, Run Configurations, perfis locais e caminhos fixos em testes e
scripts. Ele reproduziu o efeito do `.gitignore` num repositório de rascunho. Veredito: **aprovado com ressalvas** (0
bloqueantes, 6 importantes, 8 sugestões). O layout resolve o problema de leitura, e a mecânica de Maven e Docker se
confirmou; as falhas estavam no inventário de D7 e D8 e nos critérios de pronto do D9. Todas as ressalvas foram
aceitas; na R-12, o dono do projeto manteve o `frontend/web`.

| ID | Severidade | Tema | Decisão | Onde |
|---|---|---|---|---|
| R-01 | Importante | O `.gitignore` ignoraria `tools/coverage` sem aviso | Aceito: só `/frontend/web/coverage/` é ignorado; critério 2 | D5, D9 |
| R-02 | Importante | O gate de cobertura poderia passar sem dados | Aceito: linha de base e critério 1 com o exec agregado e a mensagem do gate | D9 |
| R-03 | Importante | A busca de caminhos antigos não achava o que prometia | Aceito: duas buscas com os comandos exatos | D9 |
| R-04 | Importante | Itens de build e compose fora do inventário (`dependencyManagement`, `MODULE` do compose, volume do pgAdmin, `.dockerignore`) | Aceito; critério 6 para o pgAdmin | D2, D6, D7, D9 |
| R-05 | Importante | Documentação e comentários fora da lista | Aceito: a lista passa a ser o que as buscas acharem, com o roteiro citado | D8 |
| R-06 | Importante | O nome `api` só pela metade | Aceito: tabelas do que muda e do que não muda, com o motivo | D2 |
| R-07 | Sugestão | "Nenhum comportamento muda" e "mesmo conteúdo" não eram exatos | Aceito: restrito à aplicação; nomes visíveis listados; critério 4 com o `jar tf` | D2, D9, §4 |
| R-08 | Sugestão | §7 incompleto | Aceito: ADR-0002 D8 e D9 e ADR-0003 D8 | §7, cabeçalho |
| R-09 | Sugestão | O R5 descrevia mal como o Git trata renomeações | Aceito: R5 reescrito; critério 3 | D9, §6 |
| R-10 | Sugestão | Caminhos presos à profundidade | Aceito: `${maven.multiModuleProjectDirectory}`, com o `workingDirectory` uma vez no pom raiz | D7, §3, R7 |
| R-11 | Sugestão | Relatórios que somem sem falhar e nomes dos checks | Aceito: `error` no `jacoco-report`, `name` na matriz, critério 8 e R6 | D8, D9, §6 |
| R-12 | Sugestão | Pastas com uma subpasta só | Mantido o `frontend/web`, por decisão do dono do projeto; custo registrado | §3, D1 |
| R-13 | Sugestão | O Playwright perderia o `.editorconfig` e o `.prettierrc` | Aceito: cópias em `e2e/playwright` | D4 |
| R-14 | Sugestão | Transição do ambiente local | Aceito: `down` com o compose antigo (O0, README) e conferência da IDE | D8, §5, R1, R3 |

**Afirmações reconferidas pelo revisor:**
- Os módulos movidos precisam do `relativePath`.
- O Spring Boot procura o `compose.yaml` no diretório de trabalho; nenhum perfil define `spring.docker.compose.file`.
- Os `COPY --parents` de dois níveis cobrem todos os módulos, e o BuildKit aceita os de um nível sem correspondência.
- O `-pl` e o `cp` do Dockerfile funcionam com caminho.
- O `ImportScriptTest` lê o script a partir da pasta do módulo.
- O `ModularityTest` e o prefixo `app.*` não dependem da pasta.
- O Playwright não tem caminho apontando para `web/`.
- Os volumes do compose não dependem do nome do serviço.
- O `spring-boot:run` sem `-pl` sobe a primeira aplicação do reactor.

## 9. Notas de implementação

Implementado em 2026-11-06, num commit só (O0 a O2).

**Critérios do D9**

| # | Critério | Resultado |
|---|---|---|
| 1 | `./mvnw clean verify` igual à linha de base | Verde. Os mesmos testes por módulo: `shared` 32, `ratelimit` 28, `security` 24, `account` 7, `link` 160, `analytics` 20, `redirect` 10, `api` 94, `auth-server` 49 e `bff` 46, que somam 470 em Java, e 74 no Vitest. Gate em 1510 de 1555 linhas (97,1%), 22 linhas "Loading execution data file", `tools/coverage/target/jacoco-merged.exec` presente e "All coverage checks have been met" |
| 2 | `git check-ignore -v tools/coverage/pom.xml` | Não imprime nada |
| 3 | `git diff --cached -M --name-status` | 216 `R`, 14 `M` e 3 `A`: este ADR e as duas cópias do D4. Nenhum `D` |
| 4 | `jar tf` dos três `*-exec.jar` | Iguais aos da linha de base: 299, 301 e 269 entradas, fora `META-INF/maven/**` e `build-info.properties` |
| 5 | Imagens e stack | `encurtador-api`, `encurtador-auth-server` e `encurtador-bff` construídas pelo compose, e os três serviços saudáveis. O `/actuator/info` da API mostra `build.artifact` `encurtador-url-api`, e os logs dela saem com `service.name` `encurtador-api` |
| 6 | pgAdmin | O servidor `encurtador-url` aparece cadastrado. Num pgAdmin descartável, com volume novo e a mesma montagem, ele foi importado de `infra/pgadmin/servers.json` |
| 7 | Ponta a ponta | `e2e/cli/ponta-a-ponta.sh` passou, e o Playwright (`npm --prefix e2e/playwright test`) passou nos 3 testes |
| 8 | CI | Depende do primeiro run depois do push: o artefato `jacoco-report` e os nomes curtos dos checks; a proteção da `main` (R6) também se confere ali |
| 9 | Buscas | A de nomes não acha nada. A de caminhos acha o `# syntax=docker/dockerfile:1` e mais dois falsos positivos: as entradas aninhadas da árvore do README (`auth-server/`, `bff/`, `web/` e `coverage/` dentro dos grupos) e o `Path.of("scripts/importar-usuarios.sql")` do `ImportScriptTest`, relativo à pasta do módulo |

**Desvios e decisões da implementação**

- **Linha de base da cobertura.** O gate mede o exec unificado sobre as classes descompactadas no `coverage`: 1510 de
  1555 linhas. O relatório HTML agregado, que lê o exec de cada módulo, mostra 1509 de 1554. Os dois números se
  repetiram depois da mudança. Quando o gate passa, o `jacoco:check` não imprime os números, então eles foram medidos
  com o `org.jacoco.core` sobre as mesmas classes, o mesmo exec e os mesmos excludes do pom raiz.
- **Run Configurations.** O `git mv` as renomeou para `api_local.xml` e `api_local_debug.xml`, mas o IntelliJ, aberto,
  regravou as duas com o nome que ele gera para "api (local)": `api__local_.xml` e `api__local__debug_.xml`. Ficaram
  esses nomes, que a IDE manteria a cada gravação. O conteúdo é o do D8, e a IDE já reconhece o módulo
  `encurtador-url-api` (R1).
- **Cópias do `.editorconfig` e do `.prettierrc` (D4).** Com cópias idênticas, o Git atribuía o histórico de `web/`
  às cópias em `e2e/playwright`, e não aos arquivos em `frontend/web` (R5). Por isso as cópias são diferentes do
  original: o `.editorconfig` tem uma linha de comentário a mais, e o `.prettierrc` não tem a regra do parser Angular
  para `*.html`, que o Playwright não usa.
- **`.gitignore` e `.dockerignore`.** Além do que pedem D5 e D6, `web/node/` virou `frontend/web/node/`.
- **Compose.** O serviço `api` também declara `HTTP_PORT` e `MANAGEMENT_PORT`, como os outros dois, embora sejam os
  padrões do Dockerfile.
- **README:**
  - Os comandos de desenvolvimento sobem a API com `-pl apps/api -am`, como as outras aplicações. Sem o `-pl`, o
    `./mvnw spring-boot:run` continua subindo a API (D7), mas antes compila todos os módulos do reactor, inclusive o
    painel.
  - A transição local (D8) ganhou o `docker compose --profile app down --remove-orphans`, para quem atualizou o
    repositório com a stack antiga no ar.
- **Diretório de trabalho.** O pom efetivo das três aplicações mostra a raiz do repositório. Subindo a API com
  `./mvnw -pl apps/api -am spring-boot:run`, ela usou o `compose.yaml` da raiz.
- **Vocabulário.** "O app" virou "a API" no README. Nos comentários de código, a troca só aconteceu nas linhas que já
  mudavam por causa de um caminho ou nome antigo.
- **Ambiente local.** A stack foi derrubada com o compose antigo antes da mudança (O0) e subiu pelo novo; os volumes
  continuaram.
