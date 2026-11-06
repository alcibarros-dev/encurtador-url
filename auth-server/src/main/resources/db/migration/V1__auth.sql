-- Schema "auth" do servidor de autorização (ADR-0003, D8), com histórico Flyway próprio.

create table credentials (
    id            bigint generated always as identity primary key,
    subject       varchar(64)  not null,                -- sub dos tokens: id antigo em texto ou UUID
    email         varchar(254) not null,
    password_hash varchar(255) not null,                 -- {bcrypt}…, do DelegatingPasswordEncoder
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    constraint ux_credentials_subject unique (subject),
    constraint ux_credentials_email unique (email),
    constraint ck_credentials_email_lower check (email = lower(email))
);

-- Schema fornecido pelo Spring Authorization Server, com blob → text e timestamp → timestamptz, como ele orienta
-- para o PostgreSQL.
create table oauth2_authorization (
    id                            varchar(100)  not null,
    registered_client_id          varchar(100)  not null,
    principal_name                varchar(200)  not null,
    authorization_grant_type      varchar(100)  not null,
    authorized_scopes             varchar(1000) default null,
    attributes                    text          default null,
    state                         varchar(500)  default null,
    authorization_code_value      text          default null,
    authorization_code_issued_at  timestamptz   default null,
    authorization_code_expires_at timestamptz   default null,
    authorization_code_metadata   text          default null,
    access_token_value            text          default null,
    access_token_issued_at        timestamptz   default null,
    access_token_expires_at       timestamptz   default null,
    access_token_metadata         text          default null,
    access_token_type             varchar(100)  default null,
    access_token_scopes           varchar(1000) default null,
    oidc_id_token_value           text          default null,
    oidc_id_token_issued_at       timestamptz   default null,
    oidc_id_token_expires_at      timestamptz   default null,
    oidc_id_token_metadata        text          default null,
    refresh_token_value           text          default null,
    refresh_token_issued_at       timestamptz   default null,
    refresh_token_expires_at      timestamptz   default null,
    refresh_token_metadata        text          default null,
    user_code_value               text          default null,
    user_code_issued_at           timestamptz   default null,
    user_code_expires_at          timestamptz   default null,
    user_code_metadata            text          default null,
    device_code_value             text          default null,
    device_code_issued_at         timestamptz   default null,
    device_code_expires_at        timestamptz   default null,
    device_code_metadata          text          default null,
    primary key (id)
);
-- A troca de senha encerra as autorizações do usuário (D9).
create index ix_oauth2_authorization_principal on oauth2_authorization (principal_name);
