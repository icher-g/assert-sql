package io.assertsql;

import io.sqm.core.dialect.SqlDialectId;
import io.sqm.parser.ansi.AnsiSpecs;
import io.sqm.parser.mysql.spi.MySqlSpecs;
import io.sqm.parser.oracle.spi.OracleSpecs;
import io.sqm.parser.postgresql.spi.PostgresSpecs;
import io.sqm.parser.spi.Specs;
import io.sqm.parser.sqlserver.spi.SqlServerSpecs;
import io.sqm.render.ansi.spi.AnsiDialect;
import io.sqm.render.mysql.spi.MySqlDialect;
import io.sqm.render.oracle.spi.OracleDialect;
import io.sqm.render.postgresql.spi.PostgresDialect;
import io.sqm.render.spi.SqlDialect;
import io.sqm.render.sqlserver.spi.SqlServerDialect;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shared SQM parser and renderer selection for single statements and collections.
 */
record SqlDialects(Supplier<Specs> parser, Supplier<SqlDialect> renderer) {
    static SqlDialects of(SqlDialectId dialect) {
        Objects.requireNonNull(dialect, "dialect");
        return switch (dialect.value()) {
            case "ansi" -> new SqlDialects(AnsiSpecs::new, AnsiDialect::new);
            case "postgresql" -> new SqlDialects(PostgresSpecs::new, PostgresDialect::new);
            case "mysql" -> new SqlDialects(MySqlSpecs::new, MySqlDialect::new);
            case "sqlserver" -> new SqlDialects(SqlServerSpecs::new, SqlServerDialect::new);
            case "oracle" -> new SqlDialects(OracleSpecs::new, OracleDialect::new);
            default -> throw new IllegalArgumentException("Unsupported SQL dialect: " + dialect.value());
        };
    }
}
