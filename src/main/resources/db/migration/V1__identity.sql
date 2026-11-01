-- F1 · módulo identity: contas de usuário e refresh tokens (ADR-0001, D6 e D9).
-- Instantes vêm sempre do Clock da aplicação; por isso as colunas não têm default now().

create table users (
    id            bigint generated always as identity primary key,
    email         varchar(254) not null,
    password_hash varchar(255) not null,               -- comporta Argon2 num upgrade do DelegatingPasswordEncoder
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    constraint ux_users_email unique (email),
    constraint ck_users_email_lower check (email = lower(email))
);

create table refresh_tokens (
    id                bigint generated always as identity primary key,
    user_id           bigint      not null references users (id),
    family_id         uuid        not null,
    token_hash        char(64)    not null,            -- SHA-256 hex; o token em claro nunca é guardado
    expires_at        timestamptz not null,            -- validade deste token (≤ 7 dias)
    family_expires_at timestamptz not null,            -- validade absoluta da sessão (30 dias desde o login)
    revoked_at        timestamptz,
    created_at        timestamptz not null,
    constraint ux_refresh_tokens_hash unique (token_hash)
);
create index ix_refresh_tokens_user   on refresh_tokens (user_id);
create index ix_refresh_tokens_family on refresh_tokens (family_id);
