-- F2: links curtos (ADR-0001, D3, D5 e D6). Migração aplicada nunca é editada.

create table links (
    id           bigint generated always as identity primary key,
    code         varchar(32) collate "C" not null,       -- diferencia maiúsculas de minúsculas (D3)
    target_url   varchar(2048) not null,                  -- já normalizada em ASCII (D10)
    owner_id     bigint        not null references users (id),
    custom_alias boolean       not null,
    status       varchar(16)   not null check (status in ('ACTIVE', 'DELETED')),  -- EXPIRED é derivado (D5)
    created_at   timestamptz   not null,
    expires_at   timestamptz   not null,
    deleted_at   timestamptz,
    constraint ux_links_code unique (code),               -- unicidade do código por construção, nunca por consulta
    constraint ck_links_deleted check ((status = 'DELETED') = (deleted_at is not null)),
    constraint ck_links_expiry  check (expires_at > created_at)
);

create index ix_links_owner_created on links (owner_id, created_at desc);
create index ix_links_owner_active  on links (owner_id, expires_at) where status = 'ACTIVE';   -- cota
