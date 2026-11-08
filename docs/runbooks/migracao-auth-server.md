# Roteiro: migração das contas para o `auth-server`

Passo a passo da virada descrita no [ADR-0003](../adr/0003-servidor-de-autorizacao.md) (D8), num banco que já tem
usuários cadastrados pelo login antigo do app. Em banco novo (Testcontainers, CI, primeira subida), nada disto é
necessário: o `auth-server` cria o schema `auth` vazio, e o app cria as contas no primeiro acesso.

A migração segue o padrão *expand and contract*. Até o passo 4, tudo é reversível.

| Passo | Quem | O que faz |
|---|---|---|
| 1 | `auth-server`, Flyway V1 | Cria o schema `auth` com `credentials` e `oauth2_authorization` |
| 2 | Script, uma vez | Copia `id`, e-mail, hash da senha e datas de `public.users` para `auth.credentials`, com `subject = id` em texto |
| 3 | App, Flyway V4 | Renomeia `users` para `accounts` e acrescenta `subject = id` em texto |
| 4 | App, Flyway V5 | Depois da virada confirmada, remove `password_hash`, `email` e `refresh_tokens` (até lá, o app sobe com `SPRING_FLYWAY_TARGET=4`) |

## Antes de começar

- Faça backup do banco (`pg_dump`). O passo 4 apaga dados.
- Na virada, todos os usuários fazem login de novo: os refresh tokens antigos não são migrados.
- Fora do ambiente local, o `auth-server` usa um usuário de banco próprio, com acesso só ao schema `auth`. O script do
  passo 2 lê `public.users`, então rode-o com um usuário que leia os dois schemas.

## 1. Subir o `auth-server`

Suba o `auth-server` apontando para o mesmo PostgreSQL. A V1 dele cria o schema `auth`:

```bash
docker compose up -d auth-server
```

Confira: `select count(*) from auth.credentials;` responde `0`.

## 2. Importar as credenciais

O script é idempotente (`ON CONFLICT (subject) DO NOTHING`) e funciona tanto antes quanto depois da V4 do app, porque
lê `public.users` ou, se ela já tiver sido renomeada, `public.accounts`:

```bash
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U encurtador -d encurtador \
  < infra/auth-server/importar-usuarios.sql
```

Confira que todos os usuários vieram:

```sql
select (select count(*) from public.users) as usuarios,
       (select count(*) from auth.credentials where subject ~ '^[0-9]+$') as importados;
```

Os hashes bcrypt continuam válidos (`DelegatingPasswordEncoder`): cada usuário entra no `auth-server` com a senha de
hoje.

## 3. Atualizar o app

Suba a versão do app sem o pacote `identity` parando o Flyway na V4: a mesma versão já traz a V5, que só deve rodar
no passo 4. Enquanto durar a virada, o app precisa da variável `SPRING_FLYWAY_TARGET=4`: no compose, acrescente
`SPRING_FLYWAY_TARGET: "4"` ao `environment` do serviço `api`, o do app (uma variável só no shell não chega ao
container); fora dele, a mesma variável de ambiente. Depois:

```bash
docker compose --profile app up -d --build api
```

Confira que cada conta tem o `subject` igual ao id e que os links continuam com o mesmo dono:

```sql
select count(*) from public.accounts where subject <> id::text;   -- 0
select count(*) from public.links l left join public.accounts a on a.id = l.owner_id where a.id is null;   -- 0
```

Teste a virada de ponta a ponta com uma conta nova:

```bash
e2e/cli/ponta-a-ponta.sh
```

E com um usuário migrado: faça login pela CLI (fluxo de dispositivo, README) com o e-mail e a senha de antes e liste os
links (`GET /api/v1/links`): os links antigos dele aparecem.

**Para voltar atrás** neste ponto: suba a versão anterior do app (a do commit anterior à saída do `identity`) depois
de desfazer a V4 (`alter table accounts rename to users`, recriar as restrições de e-mail e remover `subject` e a
linha da V4 em `flyway_schema_history`). As colunas de e-mail e senha ainda estão lá.

## 4. Remover as credenciais do app

Só depois da virada confirmada (os usuários já entram pelo `auth-server`): tire o `SPRING_FLYWAY_TARGET` e reinicie o
app. A V5 remove `password_hash`, `email` e a tabela `refresh_tokens`; a partir daí, o app não guarda mais nenhum dado
de login, e o script do passo 2 não tem mais de onde ler.

## Troca de provedor

Para trocar o `auth-server` por outro provedor OIDC, como o Keycloak, veja o ADR-0003, D13: os `subject` das contas
são remapeados pelo e-mail, cruzando `auth.credentials` com o export do novo provedor, e o app é reiniciado para
descartar o cache de contas.
