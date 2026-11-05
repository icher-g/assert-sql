package io.assertsql.examples.fixtures;

/**
 * Small application-owned SQL builder with fixed identifiers and named placeholders.
 */
public final class UserSearchQueryBuilder {
    private boolean activeOnly = true;
    private int pageSize = 20;
    private int offset;

    /**
     * Creates a builder for active users with a default page size of 20 and offset zero.
     */
    public UserSearchQueryBuilder() {
    }

    /**
     * Selects whether the active-user predicate is included.
     *
     * @param activeOnly whether to filter active users
     * @return this builder
     */
    public UserSearchQueryBuilder activeOnly(boolean activeOnly) {
        this.activeOnly = activeOnly;
        return this;
    }

    /**
     * Sets the page to generate.
     *
     * @param pageSize positive page size
     * @param offset   non-negative row offset
     * @return this builder
     */
    public UserSearchQueryBuilder page(int pageSize, int offset) {
        if (pageSize <= 0 || offset < 0) {
            throw new IllegalArgumentException("Page size must be positive and offset must be non-negative.");
        }
        this.pageSize = pageSize;
        this.offset = offset;
        return this;
    }

    /**
     * Generates the SQL text without executing it.
     *
     * @return the generated SQL with named placeholders
     */
    public String build() {
        var sql = new StringBuilder("SELECT u.id, u.name, p.display_name AS profile_name FROM users u ");
        sql.append("LEFT JOIN user_profiles p ON p.user_id = u.id WHERE u.tenant_id = :tenantId");
        if (activeOnly) {
            sql.append(" AND u.active = :active");
        }
        sql.append(" ORDER BY u.created_at DESC, u.id ASC LIMIT ").append(pageSize).append(" OFFSET ").append(offset);
        return sql.toString();
    }
}
