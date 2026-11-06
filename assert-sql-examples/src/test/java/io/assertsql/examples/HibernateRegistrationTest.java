package io.assertsql.examples;

import io.assertsql.Expect;
import io.assertsql.examples.fixtures.HibernateSqlCapture;
import io.assertsql.examples.fixtures.hibernate.HibernateDatabase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.sqm.core.dialect.SqlDialectId.ANSI;
import static org.junit.jupiter.api.Assertions.*;

class HibernateRegistrationTest {
    @Test
    void routesRealHibernateSqlToTheFactoryInspectorUnlessTheSessionOverridesIt() {
        // SQL inspected: SELECT u1_0.id FROM users u1_0 WHERE u1_0.tenant_id IN (?) ORDER BY u1_0.id
        var shared = new HibernateSqlCapture();
        var local = new HibernateSqlCapture();
        try (var database = HibernateDatabase.create(shared)) {
            // Fixture INSERTs were captured during real Hibernate persistence; exclude them from the query window.
            assertFalse(shared.statements().isEmpty());
            shared.clear();
            try (var session = database.sessionFactory().openSession()) {
                var ids = session.createQuery("select u.id from ExampleUser u where u.tenantId in :tenantIds order by u.id", Long.class)
                    .setParameterList("tenantIds", List.of(42L))
                    .getResultList();
                assertEquals(List.of(1L, 2L), ids);
            }
            assertEquals(1, shared.statements().size());
            var factoryExpectation = Expect.sql(ANSI).select().table("users").column("tenant_id").positionalParameterCount(1).build();
            factoryExpectation.assertMatches(shared.statements().getFirst());
            var factorySnapshot = shared.statements();
            try (var session = database.sessionFactory().withOptions().statementInspector(local).openSession()) {
                var ids = session.createQuery("select u.id from ExampleUser u where u.tenantId in :tenantIds order by u.id", Long.class)
                    .setParameterList("tenantIds", List.of(99L))
                    .getResultList();
                assertEquals(List.of(3L), ids);
            }
            assertEquals(factorySnapshot, shared.statements(), "A session inspector overrides the factory inspector.");
            assertEquals(1, local.statements().size());
            var localExpectation = Expect.sql(ANSI).select().table("users").column("tenant_id").positionalParameterCount(1).build();
            localExpectation.assertMatches(local.statements().getFirst());
            local.clear();
            assertTrue(local.statements().isEmpty());
            assertEquals(factorySnapshot, shared.statements());
        } finally {
            local.clear();
            shared.clear();
        }
    }
}
