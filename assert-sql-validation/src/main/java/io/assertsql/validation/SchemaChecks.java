package io.assertsql.validation;

import io.assertsql.Check;
import io.assertsql.Mismatch;
import io.sqm.catalog.model.CatalogSchema;
import io.sqm.core.DialectQuery;
import io.sqm.core.MergeStatement;
import io.sqm.core.Node;
import io.sqm.core.Statement;
import io.sqm.core.dialect.SqlDialectId;
import io.sqm.core.dialect.SqlFeature;
import io.sqm.core.walk.RecursiveNodeVisitor;
import io.sqm.parser.ansi.AnsiSpecs;
import io.sqm.validate.mysql.MySqlValidationDialect;
import io.sqm.validate.oracle.OracleValidationDialect;
import io.sqm.validate.postgresql.PostgresValidationDialect;
import io.sqm.validate.schema.SchemaStatementValidator;
import io.sqm.validate.sqlserver.SqlServerValidationDialect;

import java.util.List;
import java.util.Objects;

/**
 * Optional catalog assertions implemented by SQM's schema validator.
 */
public final class SchemaChecks {
    private SchemaChecks() {
    }

    /**
     * Creates a reusable check against an explicitly supplied, immutable SQM catalog.
     * The catalog is retained by reference; SQM copies caller-owned collections when constructing it.
     * Built-in dialect validators use SQM's default versions and a fresh validation context per call.
     * SQL is neither parsed nor validated here; validation runs only when the check is evaluated.
     *
     * <p>Every SQM problem becomes a mismatch with its original code, message, and available clause path,
     * in SQM's reporting order. SQM 0.5.0 exposes no severity/warning field; every problem fails the check.
     * Unsupported dialect-query models and statement kinds return VALIDATION_UNSUPPORTED instead of
     * passing silently. Statement-kind support is taken from SQM's dialect capabilities.</p>
     *
     * <p>Attach this check to the full statement to retain enclosing CTE and correlated-reference context.
     * In a scoped block it validates the selected body as a standalone statement, with no parent bindings.
     * The validation dialect must match the parser dialect chosen by the caller. No schema introspection,
     * JDBC access, query execution, or independent SQL name resolution is performed.</p>
     *
     * @param schema  non-null immutable catalog; permissive catalogs retain SQM's permissive behavior
     * @param dialect ANSI, PostgreSQL, MySQL, SQL Server, or Oracle validation rules
     * @return a thread-safe check for use with {@code Expect.Builder.satisfies(Check)}
     * @throws NullPointerException     if the schema or dialect is null
     * @throws IllegalArgumentException if the validation dialect is unsupported
     */
    public static Check validAgainst(CatalogSchema schema, SqlDialectId dialect) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(dialect, "dialect");
        return switch (dialect.value()) {
            case "ansi" -> check(SchemaStatementValidator.of(schema), new AnsiSpecs().capabilities().supports(SqlFeature.MERGE_STATEMENT));
            case "postgresql" -> {
                var extension = PostgresValidationDialect.of();
                yield check(SchemaStatementValidator.of(schema, extension), extension.capabilities().supports(SqlFeature.MERGE_STATEMENT));
            }
            case "mysql" -> {
                var extension = MySqlValidationDialect.of();
                yield check(SchemaStatementValidator.of(schema, extension), extension.capabilities().supports(SqlFeature.MERGE_STATEMENT));
            }
            case "sqlserver" -> {
                var extension = SqlServerValidationDialect.of();
                yield check(SchemaStatementValidator.of(schema, extension), extension.capabilities().supports(SqlFeature.MERGE_STATEMENT));
            }
            case "oracle" -> {
                var extension = OracleValidationDialect.of();
                yield check(SchemaStatementValidator.of(schema, extension), extension.capabilities().supports(SqlFeature.MERGE_STATEMENT));
            }
            default -> throw new IllegalArgumentException("Unsupported validation dialect: " + dialect.value());
        };
    }

    private static Check check(SchemaStatementValidator validator, boolean mergeSupported) {
        return statement -> validate(validator, Objects.requireNonNull(statement, "statement"), mergeSupported);
    }

    private static List<Mismatch> validate(SchemaStatementValidator validator, Statement statement, boolean mergeSupported) {
        var unsupported = new UnsupportedStatements(mergeSupported);
        unsupported.accept(statement);
        if (unsupported.mismatch != null) {
            return List.of(unsupported.mismatch);
        }
        return validator.validate(statement)
            .problems()
            .stream()
            .map(problem ->
                new Mismatch(problem.code().name(), problem.clausePath() == null ? "" : problem.clausePath(), problem.message())
            )
            .toList();
    }

    private static final class UnsupportedStatements extends RecursiveNodeVisitor<Void> {
        private final boolean mergeSupported;
        private Mismatch mismatch;

        private UnsupportedStatements(boolean mergeSupported) {
            this.mergeSupported = mergeSupported;
        }

        @Override
        protected Void defaultResult() {
            return null;
        }

        @Override
        protected Void accept(Node node) {
            if (node instanceof DialectQuery) {
                mismatch = new Mismatch("VALIDATION_UNSUPPORTED", "", "Schema validation does not support this dialect-specific query model.");
            }
            if (node instanceof MergeStatement && !mergeSupported) {
                mismatch = new Mismatch("VALIDATION_UNSUPPORTED", "merge", "MERGE is unsupported by the configured SQM dialect capabilities.");
            }
            return mismatch != null ? null : super.accept(node);
        }
    }
}
