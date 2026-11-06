package io.assertsql.examples;

import io.assertsql.Expect;
import io.assertsql.examples.fixtures.HibernateSqlCapture;
import io.assertsql.examples.fixtures.hibernate.HibernateDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static io.sqm.core.dialect.SqlDialectId.ANSI;
import static org.junit.jupiter.api.Assertions.*;

/** Capture lifecycle and concurrency exercised by real Hibernate queries. */
class HibernateSqlCaptureTest {
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
    void preservesDuplicateCallbacksAndSnapshotsAcrossResetAndReuse() {
        // SQL inspected twice: SELECT u1_0.id FROM users u1_0 WHERE u1_0.tenant_id IN (?) ORDER BY u1_0.id
        var capture = new HibernateSqlCapture();
        try {
            assertTrue(capture.statements().isEmpty());
            assertEquals(List.of(1L, 2L), readUserIds(capture, 42L));
            var snapshot = capture.statements();
            assertEquals(1, snapshot.size());
            assertThrows(UnsupportedOperationException.class, snapshot::clear);
            assertEquals(List.of(1L, 2L), readUserIds(capture, 42L));
            assertEquals(List.of(snapshot.getFirst(), snapshot.getFirst()), capture.statements());
            capture.clear();
            assertTrue(capture.statements().isEmpty());
            assertEquals(1, snapshot.size());
            assertEquals(List.of(3L), readUserIds(capture, 99L));
            assertEquals(1, capture.statements().size());
            var expectation = Expect.sql(ANSI).select().table("users").parameterCount(1).build();
            expectation.assertMatches(capture.statements().getFirst());
        } finally {
            capture.clear();
        }
    }

    @Test
    void sessionCapturesIsolateParallelQueriesAndCleanup() throws Exception {
        // Each session executes: SELECT u1_0.id FROM users u1_0 WHERE u1_0.tenant_id IN (?) ORDER BY u1_0.id
        var first = new HibernateSqlCapture();
        var second = new HibernateSqlCapture();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var firstResult = workers.submit(() -> readUserIds(first, 42L));
            var secondResult = workers.submit(() -> readUserIds(second, 99L));
            assertEquals(List.of(1L, 2L), firstResult.get(10, TimeUnit.SECONDS));
            assertEquals(List.of(3L), secondResult.get(10, TimeUnit.SECONDS));
            assertEquals(1, first.statements().size());
            assertEquals(1, second.statements().size());
            var secondSnapshot = second.statements();
            first.clear();
            assertTrue(first.statements().isEmpty());
            assertEquals(secondSnapshot, second.statements());
        } finally {
            first.clear();
            second.clear();
        }
    }

    @Test
    void sharedCaptureCollectsEveryQueryFromConcurrentHibernateSessions() throws Exception {
        // Four sessions each execute the same tenant-list SELECT ten times, with different bound tenant IDs.
        var capture = new HibernateSqlCapture();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var tasks = IntStream.range(0, 4).<Callable<Void>>mapToObj(worker -> () -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                long tenantId = worker % 2 == 0 ? 42L : 99L;
                var expectedIds = tenantId == 42L ? List.of(1L, 2L) : List.of(3L);
                for (int i = 0; i < 10; i++) {
                    assertEquals(expectedIds, readUserIds(capture, tenantId));
                    assertFalse(capture.statements().isEmpty());
                }
                return null;
            }).toList();
            var results = tasks.stream().map(workers::submit).toList();
            start.countDown();
            for (var result : results) {
                result.get(10, TimeUnit.SECONDS);
            }
            assertEquals(40, capture.statements().size());
        } finally {
            capture.clear();
        }
        assertTrue(capture.statements().isEmpty());
    }

    private static List<Long> readUserIds(HibernateSqlCapture capture, long tenantId) {
        try (var session = database.sessionFactory().withOptions().statementInspector(capture).openSession()) {
            return session.createQuery("select u.id from ExampleUser u where u.tenantId in :tenantIds order by u.id", Long.class)
                .setParameterList("tenantIds", List.of(tenantId))
                .getResultList();
        }
    }
}
