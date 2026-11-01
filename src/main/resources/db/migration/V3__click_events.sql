-- F4: eventos de clique (ADR-0001, D6 e D8). Migração aplicada nunca é editada.

create table click_events (
    event_id      uuid         primary key,            -- UUIDv7; idempotência do consumidor
    link_id       bigint       not null references links (id),
    occurred_at   timestamptz  not null,               -- instante do redirect, não do consumo (P4)
    referrer_host varchar(255),                         -- só o host do Referer; sem IP nem user-agent (RNF08)
    received_at   timestamptz  not null
);

create index ix_click_events_link_time on click_events (link_id, occurred_at);
