-- ADR-0003 · fase A4: depois da virada confirmada, o app deixa de guardar dados de login (D8, passo 4). As
-- credenciais estão em auth.credentials desde o script de importação, que precisa rodar antes desta migração.
-- Num banco que já tem usuários, a primeira subida sem o identity usa SPRING_FLYWAY_TARGET=4 e só aplica esta
-- migração quando a virada for confirmada (docs/runbooks/migracao-auth-server.md).

alter table accounts drop column email;
alter table accounts drop column password_hash;
drop table refresh_tokens;
