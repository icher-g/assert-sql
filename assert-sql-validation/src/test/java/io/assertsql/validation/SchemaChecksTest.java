package io.assertsql.validation;

import io.assertsql.Expect;
import io.assertsql.Match;
import io.sqm.catalog.model.CatalogColumn;
import io.sqm.catalog.model.CatalogSchema;
import io.sqm.catalog.model.CatalogTable;
import io.sqm.catalog.model.CatalogType;
import io.sqm.core.DialectQuery;
import io.sqm.core.Expression;
import io.sqm.core.Query;
import io.sqm.core.QueryTable;
import io.sqm.core.Statement;
import io.sqm.core.dialect.SqlDialectId;
import io.sqm.core.walk.NodeVisitor;
import io.sqm.parser.postgresql.spi.PostgresSpecs;
import io.sqm.parser.spi.ParseContext;
import io.sqm.validate.api.ValidationResult;
import io.sqm.validate.mysql.MySqlValidationDialect;
import io.sqm.validate.postgresql.PostgresValidationDialect;
import io.sqm.validate.schema.SchemaStatementValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static io.sqm.core.dialect.SqlDialectId.*;
import static org.junit.jupiter.api.Assertions.*;

class SchemaChecksTest {
    private static final CatalogSchema SCHEMA = CatalogSchema.of(
        CatalogTable.of(
            "app",
            "users",
            CatalogColumn.of("id", CatalogType.LONG),
            CatalogColumn.of("name", CatalogType.STRING),
            CatalogColumn.of("active", CatalogType.BOOLEAN),
            CatalogColumn.of("tenant_id", CatalogType.LONG)
        ),
        CatalogTable.of(
            "app",
            "orders",
            CatalogColumn.of("id", CatalogType.LONG),
            CatalogColumn.of("user_id", CatalogType.LONG),
            CatalogColumn.of("total", CatalogType.DECIMAL)
        ),
        CatalogTable.of(
            "app",
            "incoming",
            CatalogColumn.of("id", CatalogType.LONG),
            CatalogColumn.of("name", CatalogType.STRING)
        )
    );

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT u.id, u.name FROM app.users u WHERE u.active = true ORDER BY u.id",
        "SELECT u.id, o.total FROM app.users u JOIN app.orders o ON o.user_id = u.id",
        "WITH active_users AS (SELECT id, name FROM app.users WHERE active = true) SELECT a.id FROM active_users a",
        "SELECT q.id FROM (SELECT id FROM app.users) q",
        "SELECT u.id FROM app.users u WHERE EXISTS (SELECT 1 FROM app.orders o WHERE o.user_id = u.id)",
        "WITH RECURSIVE ids(id) AS (SELECT id FROM app.users UNION ALL SELECT id FROM ids) SELECT id FROM ids",
        "INSERT INTO app.users (id, name, active, tenant_id) VALUES (1, 'Ada', true, 42)",
        "UPDATE app.users SET name = 'Ada' WHERE id = 1",
        "DELETE FROM app.users WHERE id = 1",
        "WITH changed AS (UPDATE app.users SET active = false WHERE id = 1 RETURNING id) SELECT id FROM changed",
        "MERGE INTO app.users u USING app.incoming i ON u.id = i.id WHEN MATCHED THEN UPDATE SET u.name = i.name"
    })
    void acceptsSupportedQueryAndDmlModelsExactlyAsSqmDoes(String sql) {
        var nativeResult = nativeValidator().validate(parse(sql));
        assertTrue(nativeResult.ok(), () -> nativeResult.problems().toString());
        var expectation = Expect.sql(POSTGRESQL).satisfies(SchemaChecks.validAgainst(SCHEMA, POSTGRESQL)).build();
        expectation.assertMatches(sql);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SELECT id FROM app.missing | TABLE_NOT_FOUND",
        "SELECT u.missing FROM app.users u | COLUMN_NOT_FOUND",
        "SELECT other.id FROM app.users u | UNKNOWN_TABLE_ALIAS",
        "SELECT id FROM app.users u JOIN app.orders o ON o.user_id = u.id | COLUMN_AMBIGUOUS",
        "SELECT u.id FROM app.users u JOIN app.orders o ON o.missing = u.id | COLUMN_NOT_FOUND",
        "SELECT id FROM app.users WHERE id = 'text' | TYPE_MISMATCH",
        "WITH ids AS (SELECT id FROM app.users) SELECT missing FROM ids | COLUMN_NOT_FOUND",
        "SELECT u.id FROM app.users u WHERE EXISTS (SELECT 1 FROM app.orders o WHERE o.missing = u.id) | COLUMN_NOT_FOUND",
        "INSERT INTO app.users (missing) VALUES (1) | COLUMN_NOT_FOUND",
        "UPDATE app.users SET missing = 1 WHERE id = 1 | COLUMN_NOT_FOUND",
        "DELETE FROM app.users WHERE missing = 1 | COLUMN_NOT_FOUND",
        "MERGE INTO app.users u USING app.incoming i ON u.id = i.missing WHEN MATCHED THEN UPDATE SET name = i.name | COLUMN_NOT_FOUND"
    })
    void retainsSqmProblemCodesMessagesPathsAndReportingOrder(String sql, String expectedCode) {
        var nativeResult = nativeValidator().validate(parse(sql));
        assertFalse(nativeResult.ok());
        var result = Expect.sql(POSTGRESQL).satisfies(SchemaChecks.validAgainst(SCHEMA, POSTGRESQL)).build().evaluate(sql);
        assertSameProblems(nativeResult, result);
        assertTrue(result.mismatches().stream().anyMatch(mismatch -> mismatch.code().equals(expectedCode)), result::describe);
        var error = assertThrows(AssertionError.class, result::assertMatches);
        assertTrue(error.getMessage().contains(expectedCode));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ansi", "postgresql", "mysql", "sqlserver", "oracle"})
    void commonSchemaChecksUseEachConfiguredDialect(String name) {
        var dialect = SqlDialectId.of(name);
        var expectation = Expect.sql(dialect).satisfies(SchemaChecks.validAgainst(SCHEMA, dialect)).build();
        expectation.assertMatches("SELECT id, name FROM app.users");
        var result = expectation.evaluate("SELECT missing FROM app.users");
        assertTrue(result.mismatches().stream().anyMatch(mismatch -> mismatch.code().equals("COLUMN_NOT_FOUND")), result::describe);
    }

    @Test
    void retainsSqmBoundaryForUnknownDerivedTableOutputColumns() {
        var sql = "SELECT q.missing FROM (SELECT id FROM app.users) q";
        // SQM 0.5.0 currently accepts this unknown derived output column. The adapter adds no independent resolver.
        var nativeResult = nativeValidator().validate(parse(sql));
        assertTrue(nativeResult.ok());
        var result = Expect.sql(POSTGRESQL).satisfies(SchemaChecks.validAgainst(SCHEMA, POSTGRESQL)).build().evaluate(sql);
        assertSameProblems(nativeResult, result);
    }

    @Test
    void qualifiedNamesResolveAnOtherwiseAmbiguousCatalogTable() {
        var schema = CatalogSchema.of(SCHEMA.tables().getFirst(), CatalogTable.of("other", "users", CatalogColumn.of("id", CatalogType.LONG)));
        var expectation = Expect.sql(POSTGRESQL).satisfies(SchemaChecks.validAgainst(schema, POSTGRESQL)).build();
        expectation.assertMatches("SELECT id FROM app.users");
        var result = expectation.evaluate("SELECT id FROM users");
        assertTrue(result.mismatches().stream().anyMatch(mismatch -> mismatch.code().equals("TABLE_AMBIGUOUS")), result::describe);
        var nativeResult = SchemaStatementValidator.of(schema, PostgresValidationDialect.of()).validate(parse("SELECT id FROM users"));
        assertSameProblems(nativeResult, result);
    }

    @Test
    void declarationIsDeferredAndParseFailuresSkipValidation() {
        var calls = new AtomicInteger();
        var validation = SchemaChecks.validAgainst(SCHEMA, POSTGRESQL);
        var expectation = Expect.sql(POSTGRESQL).satisfies(statement -> {
            calls.incrementAndGet();
            return validation.evaluate(statement);
        }).build();
        assertEquals(0, calls.get());
        var result = expectation.evaluate("SELECT FROM");
        assertFalse(result.matches());
        assertTrue(result.mismatches().stream().allMatch(mismatch -> mismatch.code().equals("PARSE_ERROR")));
        assertEquals(0, calls.get());
        expectation.assertMatches("SELECT id FROM app.users");
        assertEquals(1, calls.get());
    }

    @Test
    void validationOfAScopedBodyHasNoEnclosingCteOrCorrelationBindings() {
        var sql = "WITH ids AS (SELECT id FROM app.users) SELECT id FROM ids";
        Expect.sql(POSTGRESQL).satisfies(SchemaChecks.validAgainst(SCHEMA, POSTGRESQL)).build().assertMatches(sql);
        var outer = Expect.sql(POSTGRESQL).outer(query -> query.satisfies(SchemaChecks.validAgainst(SCHEMA, POSTGRESQL))).build().evaluate(sql);
        assertTrue(outer.mismatches().stream().anyMatch(mismatch -> mismatch.code().equals("TABLE_NOT_FOUND")), outer::describe);
        assertTrue(outer.describe().contains("outer query"));
        var correlated = "SELECT u.id FROM app.users u WHERE EXISTS (SELECT 1 FROM app.orders o WHERE o.user_id = u.id)";
        var isolated = Expect.sql(POSTGRESQL)
            .exists(query -> query.satisfies(SchemaChecks.validAgainst(SCHEMA, POSTGRESQL)))
            .build()
            .evaluate(correlated);
        assertTrue(isolated.mismatches().stream().anyMatch(mismatch -> mismatch.code().equals("UNKNOWN_TABLE_ALIAS")), isolated::describe);
    }

    @Test
    void immutableCatalogOwnershipAndPermissiveCatalogBehaviorRemainSqmDefined() {
        var columns = new ArrayList<>(List.of(CatalogColumn.of("id", CatalogType.LONG), CatalogColumn.of("name", CatalogType.STRING)));
        var tables = new ArrayList<>(List.of(CatalogTable.of("app", "users", columns)));
        var schema = CatalogSchema.of(tables);
        var expectation = Expect.sql(POSTGRESQL).satisfies(SchemaChecks.validAgainst(schema, POSTGRESQL)).build();
        columns.clear();
        tables.clear();
        assertThrows(UnsupportedOperationException.class, () -> schema.tables().clear());
        assertThrows(UnsupportedOperationException.class, () -> schema.tables().getFirst().columns().clear());
        expectation.assertMatches("SELECT id, name FROM app.users");
        var changed = CatalogSchema.of(CatalogTable.of("app", "users", CatalogColumn.of("id", CatalogType.LONG)));
        assertFalse(Expect.sql(POSTGRESQL).satisfies(SchemaChecks.validAgainst(changed, POSTGRESQL)).build().matches("SELECT name FROM app.users"));
        expectation.assertMatches("SELECT name FROM app.users");
        var permissive = Expect.sql(POSTGRESQL).satisfies(SchemaChecks.validAgainst(CatalogSchema.allowEverything(), POSTGRESQL)).build();
        permissive.assertMatches("SELECT anything FROM arbitrary_table");
    }

    @Test
    void configuredDialectFeatureProblemsArePreservedAsFailures() {
        // The parsed PostgreSQL model is intentionally checked using MySQL rules, where RETURNING is unsupported.
        var sql = "INSERT INTO app.users (id) VALUES (1) RETURNING id";
        var statement = parse(sql);
        var nativeResult = SchemaStatementValidator.of(SCHEMA, MySqlValidationDialect.of()).validate(statement);
        assertFalse(nativeResult.ok());
        var result = Match.of(SchemaChecks.validAgainst(SCHEMA, MYSQL).evaluate(statement));
        assertSameProblems(nativeResult, result);
        assertTrue(result.mismatches().stream().anyMatch(mismatch -> mismatch.code().equals("DIALECT_FEATURE_UNSUPPORTED")), result::describe);
    }

    @Test
    void unsupportedStatementKindsAndExtensionQueriesNeverSilentlyPass() {
        var merge = parse("MERGE INTO app.users u USING app.incoming i ON u.id = i.id WHEN MATCHED THEN UPDATE SET name = i.name");
        var unsupportedMerge = SchemaChecks.validAgainst(SCHEMA, MYSQL).evaluate(merge);
        assertEquals("VALIDATION_UNSUPPORTED", unsupportedMerge.getFirst().code());
        var extension = new ExtensionQuery();
        var check = SchemaChecks.validAgainst(SCHEMA, POSTGRESQL);
        assertEquals("VALIDATION_UNSUPPORTED", check.evaluate(extension).getFirst().code());
        var nested = Query.select(Expression.literal(1)).from(QueryTable.of(extension)).build();
        assertEquals("VALIDATION_UNSUPPORTED", check.evaluate(nested).getFirst().code());
    }

    @Test
    void rejectsMissingInputsAndUnknownDialectsWithoutFallback() {
        assertThrows(NullPointerException.class, () -> SchemaChecks.validAgainst(null, POSTGRESQL));
        assertThrows(NullPointerException.class, () -> SchemaChecks.validAgainst(SCHEMA, null));
        assertThrows(IllegalArgumentException.class, () -> SchemaChecks.validAgainst(SCHEMA, SqlDialectId.of("unknown")));
        assertThrows(NullPointerException.class, () -> SchemaChecks.validAgainst(SCHEMA, POSTGRESQL).evaluate(null));
    }

    @Test
    void expectationsComposeWithOrAndSupportConcurrentReuseWithoutStateRetention() throws Exception {
        var validation = SchemaChecks.validAgainst(SCHEMA, POSTGRESQL);
        var expectation = Expect.sql(POSTGRESQL).satisfies(validation).table("app.users").build();
        var fallback = Expect.sql(POSTGRESQL).structurallyEquals("SELECT 1").build();
        Expect.or(expectation, fallback).assertMatches("SELECT 1");
        try (var executor = Executors.newFixedThreadPool(4)) {
            var results = IntStream.range(0, 25).mapToObj(i -> executor.submit(() -> {
                expectation.assertMatches("SELECT id FROM app.users");
                assertFalse(expectation.matches("SELECT missing FROM app.users"));
                assertFalse(expectation.matches("invalid SQL"));
                expectation.assertMatches("SELECT name FROM app.users");
                return true;
            })).toList();
            for (var result : results) {
                assertTrue(result.get(10, TimeUnit.SECONDS));
            }
        }
    }

    private static Statement parse(String sql) {
        var result = ParseContext.of(new PostgresSpecs()).parse(Statement.class, sql);
        assertTrue(result.ok(), result::errorMessage);
        return result.value();
    }

    private static SchemaStatementValidator nativeValidator() {
        return SchemaStatementValidator.of(SCHEMA, PostgresValidationDialect.of());
    }

    private static void assertSameProblems(ValidationResult expected, Match actual) {
        assertEquals(expected.ok(), actual.matches());
        assertEquals(expected.problems().size(), actual.mismatches().size());
        for (int i = 0; i < expected.problems().size(); i++) {
            var problem = expected.problems().get(i);
            var mismatch = actual.mismatches().get(i);
            assertEquals(problem.code().name(), mismatch.code());
            assertEquals(problem.message(), mismatch.message());
            assertEquals(problem.clausePath() == null ? "" : problem.clausePath(), mismatch.path());
        }
    }

    private record ExtensionQuery() implements DialectQuery {
        @Override
        public <R> R accept(NodeVisitor<R> visitor) {
            return null;
        }
    }
}
