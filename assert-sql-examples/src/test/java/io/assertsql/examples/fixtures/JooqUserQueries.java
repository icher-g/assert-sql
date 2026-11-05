package io.assertsql.examples.fixtures;

import org.jooq.JoinType;
import org.jooq.Select;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

import static org.jooq.SQLDialect.POSTGRES;
import static org.jooq.impl.DSL.*;

/** Application query generation: construct a query without a Connection or DataSource. */
public final class JooqUserQueries {
    private JooqUserQueries() {
    }

    /**
     * Constructs a user-page query without executing it.
     * @param tenantId tenant binding value
     * @param pageSize positive page size
     * @param offset non-negative row offset
     * @param requireProfile whether to use an INNER JOIN instead of a LEFT JOIN
     * @return the query to render and inspect
     */
    public static Select<?> users(long tenantId, int pageSize, int offset, boolean requireProfile) {
        if (pageSize <= 0 || offset < 0) {
            throw new IllegalArgumentException("Page size must be positive and offset must be non-negative.");
        }
        var context = DSL.using(POSTGRES, new Settings().withRenderQuotedNames(RenderQuotedNames.EXPLICIT_DEFAULT_UNQUOTED));
        var users = table(name("users")).as("u");
        var profiles = table(name("user_profiles")).as("p");
        var userId = field(name("u", "id"), Long.class);
        var userName = field(name("u", "name"), String.class);
        var profileName = field(name("p", "display_name"), String.class);
        var profileUserId = field(name("p", "user_id"), Long.class);
        var tenant = field(name("u", "tenant_id"), Long.class);
        var createdAt = field(name("u", "created_at"));
        var joinType = requireProfile ? JoinType.JOIN : JoinType.LEFT_OUTER_JOIN;
        return context.select(userId, userName, profileName.as("profile_name"))
            .from(users.join(profiles, joinType).on(profileUserId.eq(userId)))
            .where(tenant.eq(param("tenantId", tenantId)))
            .orderBy(createdAt.desc(), userId.asc())
            .limit(inline(pageSize))
            .offset(inline(offset));
    }
}
