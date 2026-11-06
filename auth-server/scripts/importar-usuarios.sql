-- Importação única das credenciais do app para o schema auth (ADR-0003, D8, passo 2).
--
-- Roda uma vez, antes da V4 do app, com um usuário que leia public.users (ou public.accounts, se a V4 já tiver
-- rodado sem apagar as senhas) e escreva em auth.credentials:
--   psql "$DATABASE_URL" -f auth-server/scripts/importar-usuarios.sql
--
-- O subject é o id atual em texto, o mesmo que o app grava em accounts.subject: os links continuam com os mesmos
-- donos. Os hashes bcrypt continuam válidos. É idempotente: rodar de novo não duplica nada.
do $$
declare
    origem regclass := coalesce(to_regclass('public.users'), to_regclass('public.accounts'));
begin
    if origem is null then
        raise notice 'Nem public.users nem public.accounts existem: nada a importar.';
        return;
    end if;
    execute format(
        'insert into auth.credentials (subject, email, password_hash, created_at, updated_at)
         select u.id::text, u.email, u.password_hash, u.created_at, u.updated_at
         from %s u
         where u.email is not null and u.password_hash is not null
         on conflict do nothing', origem);
end
$$;
