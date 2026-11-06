package io.assertsql.examples.fixtures.hibernate;

import io.assertsql.examples.fixtures.HibernateSqlCapture;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

import java.util.UUID;

import static org.hibernate.cfg.AvailableSettings.*;

/** Owns a real Hibernate factory, mappings, seed data, and a uniquely named in-memory H2 database. */
public final class HibernateDatabase implements AutoCloseable {
    private final StandardServiceRegistry registry;
    private final SessionFactory sessionFactory;

    private HibernateDatabase(StandardServiceRegistry registry, SessionFactory sessionFactory) {
        this.registry = registry;
        this.sessionFactory = sessionFactory;
    }

    public static HibernateDatabase create() {
        return create(null);
    }

    public static HibernateDatabase create(HibernateSqlCapture sharedCapture) {
        var registry = new StandardServiceRegistryBuilder()
            .applySetting(JAKARTA_JDBC_DRIVER, "org.h2.Driver")
            .applySetting(JAKARTA_JDBC_URL, "jdbc:h2:mem:assert_sql_" + UUID.randomUUID())
            .applySetting(JAKARTA_JDBC_USER, "sa")
            .applySetting(JAKARTA_JDBC_PASSWORD, "")
            .applySetting(HBM2DDL_AUTO, "create-drop")
            .build();
        SessionFactory sessionFactory;
        try {
            var builder = new MetadataSources(registry)
                .addAnnotatedClass(User.class)
                .addAnnotatedClass(UserProfile.class)
                .buildMetadata()
                .getSessionFactoryBuilder();
            if (sharedCapture != null) {
                builder.applyStatementInspector(sharedCapture);
            }
            sessionFactory = builder.build();
        } catch (RuntimeException | Error failure) {
            StandardServiceRegistryBuilder.destroy(registry);
            throw failure;
        }
        var database = new HibernateDatabase(registry, sessionFactory);
        try {
            database.seed();
            return database;
        } catch (RuntimeException | Error failure) {
            database.close();
            throw failure;
        }
    }

    public SessionFactory sessionFactory() {
        return sessionFactory;
    }

    @Override
    public void close() {
        try {
            sessionFactory.close();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private void seed() {
        sessionFactory.inTransaction(session -> {
            var profile = new UserProfile(11L, "Ada's profile");
            session.persist(profile);
            session.persist(new User(1L, "Ada", 42L, profile));
            session.persist(new User(2L, "Linus", 42L, null));
            session.persist(new User(3L, "Other tenant", 99L, null));
        });
    }
}
