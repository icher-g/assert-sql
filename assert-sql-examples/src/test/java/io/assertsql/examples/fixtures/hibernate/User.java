package io.assertsql.examples.fixtures.hibernate;

import jakarta.persistence.*;

/**
 * Mapped user with an optional profile, used by actual Hibernate queries.
 */
@Entity(name = "ExampleUser")
@Table(name = "users")
public class User {
    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "tenant_id", nullable = false)
    private long tenantId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id")
    private UserProfile profile;

    protected User() {
    }

    public User(Long id, String name, long tenantId, UserProfile profile) {
        this.id = id;
        this.name = name;
        this.tenantId = tenantId;
        this.profile = profile;
    }
}
