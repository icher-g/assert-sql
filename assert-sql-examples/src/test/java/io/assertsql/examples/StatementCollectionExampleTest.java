package io.assertsql.examples;

import io.assertsql.Expect;
import io.assertsql.examples.fixtures.HibernateSqlCapture;
import io.assertsql.examples.fixtures.hibernate.HibernateDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.sqm.core.dialect.SqlDialectId.ANSI;
import static org.junit.jupiter.api.Assertions.*;

class StatementCollectionExampleTest {
    private static HibernateDatabase database;

    @BeforeAll
    static void createDatabase() {
        database = HibernateDatabase.create();
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void enforcesAnExplicitSelectBudgetOverRealCapturedHibernateSql() {
        /* Hibernate-generated SQL inspected (formatted for readability):
         * [0] SELECT u1_0.id FROM users u1_0 WHERE u1_0.tenant_id IN (?) ORDER BY u1_0.id
         * [1] SELECT u1_0.id FROM users u1_0 WHERE u1_0.tenant_id IN (?) ORDER BY u1_0.id
         * Separate HQL invocations supply tenant lists [42L] and [99L].
         */
        var capture = new HibernateSqlCapture();
        try (var session = database.sessionFactory()
            .withOptions()
            .statementInspector(capture)
            .openSession()) {
            var firstTenant = session.createQuery("select u.id from ExampleUser u where u.tenantId in :tenantIds order by u.id", Long.class)
                .setParameterList("tenantIds", List.of(42L))
                .getResultList();
            var secondTenant = session.createQuery("select u.id from ExampleUser u where u.tenantId in :tenantIds order by u.id", Long.class)
                .setParameterList("tenantIds", List.of(99L))
                .getResultList();
            Expect.statements(ANSI)
                .as("tenant lookup budget")
                .count(2)
                .selectCount(2)
                .selectCount(2, query -> query
                    .table("users")
                    .exactProjection("u1_0.id")
                    .parameterCount(1)
                )
                .updateCount(0)
                .build()
                .assertMatches(capture.statements());
            assertEquals(List.of(1L, 2L), firstTenant);
            assertEquals(List.of(3L), secondTenant);
        }
        finally {
            capture.clear();
        }
    }

    @Test
    void detectsAnUnexpectedRealUpdateWithoutTreatingItAsASelect() {
        /* Hibernate-generated SQL inspected (formatted for readability):
         * [0] SELECT u1_0.id FROM users u1_0 WHERE u1_0.tenant_id IN (?) ORDER BY u1_0.id
         * [1] UPDATE users u1_0 SET name = 'Changed' WHERE u1_0.tenant_id IN (?)
         * [2] SELECT u1_0.id FROM users u1_0 WHERE u1_0.tenant_id IN (?) ORDER BY u1_0.id
         * Regression: a write was added between the two permitted reads.
         */
        var capture = new HibernateSqlCapture();
        try (var session = database.sessionFactory()
            .withOptions()
            .statementInspector(capture)
            .openSession()) {
            var transaction = session.beginTransaction();
            try {
                session.createQuery("select u.id from ExampleUser u where u.tenantId in :tenantIds order by u.id", Long.class)
                    .setParameterList("tenantIds", List.of(42L))
                    .getResultList();
                var affected = session.createMutationQuery("update ExampleUser u set u.name = 'Changed' where u.tenantId in :tenantIds")
                    .setParameterList("tenantIds", List.of(42L))
                    .executeUpdate();
                session.createQuery("select u.id from ExampleUser u where u.tenantId in :tenantIds order by u.id", Long.class)
                    .setParameterList("tenantIds", List.of(99L))
                    .getResultList();
                var expectation = Expect.statements(ANSI)
                    .as("tenant lookup budget")
                    .count(2)
                    .selectCount(2, query -> query.table("users"))
                    .updateCount(0)
                    .build();
                var result = expectation.evaluate(capture.statements());
                assertFalse(result.matches());
                assertEquals(2, affected, "Hibernate executed the UPDATE against the real H2 fixture.");
                assertTrue(
                    result.mismatches()
                        .stream()
                        .anyMatch(mismatch -> mismatch.code().equals("STATEMENT_COUNT_MISMATCH"))
                );
                assertTrue(
                    result.mismatches()
                        .stream()
                        .anyMatch(mismatch -> mismatch.code().equals("UPDATE_COUNT_MISMATCH"))
                );
                assertFalse(
                    result.mismatches()
                        .stream()
                        .anyMatch(mismatch -> mismatch.code().equals("SELECT_COUNT_MISMATCH"))
                );
                assertTrue(result.describe().contains("collection.updateCount: Expected 0 matching UPDATE statements but found 1 at indices [1]."));
                var error = assertThrows(AssertionError.class, result::assertMatches);
                assertTrue(error.getMessage().contains("tenant lookup budget"));
            }
            finally {
                transaction.rollback();
            }
        }
        finally {
            capture.clear();
        }
    }
}
