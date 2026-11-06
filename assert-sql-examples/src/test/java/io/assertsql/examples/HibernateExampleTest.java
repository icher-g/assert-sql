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

/** Real Hibernate HQL execution against H2; only Hibernate invokes the inspector. */
class HibernateExampleTest {
    private static HibernateDatabase database;

    @BeforeAll
    static void createDatabaseAndSeedMappings() {
        database = HibernateDatabase.create();
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void assertsHibernateGeneratedPaginationAndPreservesUsersWithoutProfiles() {
        /* Hibernate-generated SQL inspected (formatted for readability):
         * SELECT u1_0.id, u1_0.name, p1_0.display_name
         * FROM users u1_0
         * LEFT JOIN user_profiles p1_0 ON p1_0.id = u1_0.profile_id
         * WHERE u1_0.tenant_id IN (?)
         * ORDER BY u1_0.id
         * OFFSET ? ROWS FETCH FIRST ? ROWS ONLY
         */
        var capture = new HibernateSqlCapture();
        try (var session = database.sessionFactory().withOptions().statementInspector(capture).openSession()) {
            var transaction = session.beginTransaction();
            try {
                var rows = session.createQuery("""
                    select u.id, u.name, p.displayName from ExampleUser u
                    left join u.profile p where u.tenantId in :tenantIds order by u.id
                    """, Object[].class)
                    .setParameterList("tenantIds", List.of(42L))
                    .setFirstResult(1)
                    .setMaxResults(20)
                    .getResultList();
                var statements = capture.statements();
                assertEquals(1, statements.size(), "Hibernate must generate exactly one SELECT for the projection.");
                var expectation = Expect.sql(ANSI)
                    .as("Hibernate tenant user page")
                    .select()
                    .table("users")
                    .leftJoinTable("user_profiles")
                    .exactProjection("u1_0.id", "u1_0.name", "p1_0.display_name")
                    .where("u1_0.tenant_id IN (?)")
                    .orderBy("u1_0.id")
                    .parameterCount(3)
                    .positionalParameterCount(3)
                    .build();
                expectation.assertMatches(statements.getFirst());
                // Check returned rows separately: offset 1 leaves the tenant's user without a profile.
                assertEquals(1, rows.size());
                assertArrayEquals(new Object[]{2L, "Linus", null}, rows.getFirst());
            } finally {
                transaction.rollback();
            }
        } finally {
            capture.clear();
        }
        assertTrue(capture.statements().isEmpty());
    }

    @Test
    void detectsARealInnerJoinRegressionAndItsEffectOnReturnedRows() {
        /* Hibernate-generated SQL inspected (formatted for readability):
         * SELECT u1_0.id, u1_0.name, p1_0.display_name
         * FROM users u1_0
         * JOIN user_profiles p1_0 ON p1_0.id = u1_0.profile_id
         * WHERE u1_0.tenant_id IN (?) ORDER BY u1_0.id
         * Regression: INNER JOIN excludes Linus, who has no profile.
         */
        var capture = new HibernateSqlCapture();
        try (var session = database.sessionFactory().withOptions().statementInspector(capture).openSession()) {
            var transaction = session.beginTransaction();
            try {
                var rows = session.createQuery("""
                    select u.id, u.name, p.displayName from ExampleUser u
                    join u.profile p where u.tenantId in :tenantIds order by u.id
                    """, Object[].class)
                    .setParameterList("tenantIds", List.of(42L))
                    .getResultList();
                var statements = capture.statements();
                assertEquals(1, statements.size());
                var expectation = Expect.sql(ANSI)
                    .as("Hibernate optional profiles")
                    .table("users")
                    .leftJoinTable("user_profiles")
                    .parameterCount(1)
                    .build();
                var error = assertThrows(AssertionError.class, () -> expectation.assertMatches(statements.getFirst()));
                assertTrue(error.getMessage().contains("Hibernate optional profiles"));
                assertTrue(error.getMessage().contains("JOIN_TABLE_NOT_REFERENCED"), error::getMessage);
                assertEquals(1, rows.size());
                assertArrayEquals(new Object[]{1L, "Ada", "Ada's profile"}, rows.getFirst());
            } finally {
                transaction.rollback();
            }
        } finally {
            capture.clear();
        }
    }

    @Test
    void reportsTheSqmBoundaryForCompactEqualityProducedByHibernate() {
        // SQL inspected: SELECT u1_0.id FROM users u1_0 WHERE u1_0.tenant_id=? ORDER BY u1_0.id
        var capture = new HibernateSqlCapture();
        try (var session = database.sessionFactory().withOptions().statementInspector(capture).openSession()) {
            var rows = session.createQuery("select u.id from ExampleUser u where u.tenantId = :tenantId order by u.id", Long.class)
                .setParameter("tenantId", 42L)
                .getResultList();
            assertEquals(List.of(1L, 2L), rows);
            assertEquals(1, capture.statements().size());
            var sql = capture.statements().getFirst();
            assertTrue(sql.contains("tenant_id=?"), sql);
            var expectation = Expect.sql(ANSI).select().table("users").build();
            var result = expectation.evaluate(sql);
            assertFalse(result.matches(), "SQM 0.5.0 does not separate the adjacent = and ? tokens.");
            assertEquals("PARSE_ERROR", result.mismatches().getFirst().code());
            assertThrows(AssertionError.class, result::assertMatches);
        } finally {
            capture.clear();
        }
    }
}
