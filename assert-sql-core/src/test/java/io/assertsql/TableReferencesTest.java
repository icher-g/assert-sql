package io.assertsql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.sqm.core.dialect.SqlDialectId;

import static io.sqm.core.dialect.SqlDialectId.*;
import static org.junit.jupiter.api.Assertions.*;

class TableReferencesTest {
    @Test
    void filtersDerivedAndFunctionTablesWithoutTreatingAliasesAsNames() {
        var sql = "SELECT u.id FROM (SELECT id FROM users) u " + "CROSS JOIN generate_series(1, 3) g";
        Expect.sql(POSTGRESQL).table("users").build().assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).table("u").build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).table("g").build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).table("generate_series").build().matches(sql));
    }

    @Test
    void matchesQualifiedNamesAndPhysicalReferencesInJoinsAndSubqueries() {
        var sql = "SELECT u.id FROM public.users u JOIN orders o ON o.id = u.id " + "WHERE EXISTS (SELECT 1 FROM audit.entries e WHERE e.id = u.id)";
        Expect.sql(POSTGRESQL).table("users").table("public.users").table("orders").table("audit.entries").build().assertMatches(sql);
        assertFalse(Expect.sql(POSTGRESQL).table("other.users").build().matches(sql));
        assertFalse(Expect.sql(POSTGRESQL).table("u").build().matches(sql));
    }

    @Test
    void includesNamedCteReferencesAndTheirSourcesAsCollectedBySqm() {
        var sql = "WITH u AS (SELECT id FROM users), v AS (SELECT id FROM u) SELECT id FROM v";
        Expect.sql(POSTGRESQL).table("users").build().assertMatches(sql);
        Expect.sql(POSTGRESQL).table("u").table("v").build().assertMatches(sql);
    }

    @Test
    void nonRecursiveSelfNameCanReferToAPhysicalTable() {
        Expect.sql(POSTGRESQL).table("users").build().assertMatches("WITH users AS (SELECT id FROM users) SELECT id FROM users");
    }

    @Test
    void includesRecursiveReferencesAsCollectedBySqm() {
        var sql = "WITH RECURSIVE chain AS (SELECT id FROM users UNION ALL SELECT id FROM chain) SELECT id FROM chain";
        Expect.sql(POSTGRESQL).table("users").build().assertMatches(sql);
        Expect.sql(POSTGRESQL).table("chain").build().assertMatches(sql);
    }

    @Test
    void collectsNamedReferencesAcrossNestedQueries() {
        var sql = "SELECT id FROM (WITH x AS (SELECT id FROM users) SELECT id FROM x) a " + "UNION ALL SELECT id FROM x";
        Expect.sql(POSTGRESQL).table("x").table("users").build().assertMatches(sql);
    }

    @Test
    void schemaQualifiedTableIsNotShadowedByCte() {
        Expect.sql(POSTGRESQL).table("public.users").build().assertMatches("WITH users AS (SELECT 1 AS id) SELECT id FROM public.users");
    }

    @Test
    void quotedIdentifiersRequireExactValues() {
        Expect.sql(POSTGRESQL).table("USERS").build().assertMatches("SELECT id FROM users");
        Expect.sql(ANSI).table("users").build().assertMatches("SELECT id FROM USERS");
        Expect.sql(ORACLE).table("users").build().assertMatches("SELECT id FROM USERS");
        Expect.sql(POSTGRESQL).table("\"users\"").build().assertMatches("SELECT id FROM users");
        assertFalse(Expect.sql(POSTGRESQL).table("\"users\"").build().matches("SELECT id FROM USERS"));
        assertFalse(Expect.sql(POSTGRESQL).table("USERS").build().matches("SELECT id FROM \"users\""));
        assertFalse(Expect.sql(POSTGRESQL).table("\"Users\"").build().matches("SELECT id FROM users"));
        Expect.sql(POSTGRESQL).table("\"odd.schema\".\"odd.table\"").build().assertMatches("SELECT id FROM \"odd.schema\".\"odd.table\"");
        assertFalse(Expect.sql(POSTGRESQL).table("\"Public\".users").build().matches("SELECT id FROM public.users"));
        assertFalse(Expect.sql(POSTGRESQL).table("public.users").build().matches("SELECT id FROM \"Public\".users"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ansi", "postgresql", "mysql", "sqlserver", "oracle"})
    void unquotedNamesIgnoreCaseInEveryDialect(String dialect) {
        var builder = Expect.sql(SqlDialectId.of(dialect)).table("UsErS").table("PuBlIc.USERS");
        builder.build().assertMatches("SELECT id FROM public.users");
        builder.build().assertMatches("SELECT id FROM PUBLIC.USERS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "users; DELETE FROM users", "ONLY users", "users *", "users WHERE id = 1"})
    void rejectsTableExpressionsRatherThanSilentlyMatchingThem(String name) {
        var expectation = Expect.sql(POSTGRESQL).table(name);
        assertThrows(IllegalArgumentException.class, () -> expectation.build().evaluate("SELECT id FROM users"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Users AS u", "Users u", "public.Users AS u"})
    void matchesAnExplicitAliasWithOrWithoutAs(String reference) {
        Expect.sql(POSTGRESQL).table(reference).build().assertMatches("SELECT u.id FROM public.users AS u");
    }

    @Test
    void unspecifiedAliasAllowsAnyAliasOrNoAlias() {
        var expectation = Expect.sql(POSTGRESQL).table("users").build();
        expectation.assertMatches("SELECT id FROM users");
        expectation.assertMatches("SELECT u.id FROM users u");
        expectation.assertMatches("SELECT other.id FROM users other");
    }

    @Test
    void specifiedAliasMustBelongToTheMatchingTable() {
        var expectation = Expect.sql(POSTGRESQL).table("users AS u").build();
        assertFalse(expectation.matches("SELECT id FROM users"));
        assertFalse(expectation.matches("SELECT x.id FROM users x"));
        assertFalse(expectation.matches("SELECT u.id FROM users x JOIN orders u ON x.id = u.id"));
        expectation.assertMatches("SELECT u.id FROM users x JOIN users u ON x.id = u.id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ansi", "postgresql", "mysql", "sqlserver", "oracle"})
    void unquotedAliasesIgnoreCaseInEveryDialect(String dialect) {
        Expect.sql(SqlDialectId.of(dialect)).table("Users U").build().assertMatches("SELECT u.id FROM users u");
    }

    @Test
    void quotedAliasesRequireExactValuesWhenEitherSideIsQuoted() {
        Expect.sql(POSTGRESQL).table("users AS \"u\"").build().assertMatches("SELECT u.id FROM users u");
        Expect.sql(POSTGRESQL).table("users AS u").build().assertMatches("SELECT \"u\".id FROM users \"u\"");
        assertFalse(Expect.sql(POSTGRESQL).table("users AS \"U\"").build().matches("SELECT u.id FROM users u"));
        assertFalse(Expect.sql(POSTGRESQL).table("users AS U").build().matches("SELECT \"u\".id FROM users \"u\""));
    }

    @Test
    void aliasMatchingAlsoWorksForNestedAndCteReferences() {
        Expect.sql(POSTGRESQL)
            .table("users AS src")
            .table("recent AS r")
            .build()
            .assertMatches("WITH recent AS (SELECT src.id FROM users src) SELECT r.id FROM recent r");
    }
}
