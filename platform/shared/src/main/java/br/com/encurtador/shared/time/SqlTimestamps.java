package br.com.encurtador.shared.time;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Conversão de {@link Instant} para colunas {@code timestamptz} em SQL escrito à mão ({@code JdbcClient}).
 * O driver do PostgreSQL mapeia {@link OffsetDateTime} sem ambiguidade de fuso.
 */
public final class SqlTimestamps {

    private SqlTimestamps() {
    }

    public static OffsetDateTime toSql(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static Instant fromSql(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
