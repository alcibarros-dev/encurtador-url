-- ADR-0003 · fase A2: o app deixa de guardar credenciais (D7 e D8, passo 3). A tabela users vira accounts, ligada ao
-- sub dos tokens do servidor de autorização. Os usuários de hoje mantêm o id (os links continuam com o mesmo dono) e
-- recebem como subject o próprio id em texto, o mesmo valor que o script de importação grava em auth.credentials.
-- E-mail e hash da senha ficam até a V5, que os remove junto com refresh_tokens depois da virada.

alter table users rename to accounts;
alter index users_pkey rename to accounts_pkey;
alter sequence users_id_seq rename to accounts_id_seq;

-- O sub é opaco para o app: id antigo em texto, UUID do auth-server ou o identificador de outro provedor (D13).
alter table accounts add column subject varchar(255);
update accounts set subject = id::text;
alter table accounts alter column subject set not null;
alter table accounts add constraint ux_accounts_subject unique (subject);

-- Contas criadas no primeiro acesso (D7) gravam só subject e datas.
alter table accounts alter column email drop not null;
alter table accounts alter column password_hash drop not null;
alter table accounts drop constraint ux_users_email;
alter table accounts drop constraint ck_users_email_lower;
