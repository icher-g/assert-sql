package io.assertsql.examples.fixtures.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Mapped profile used by the Hibernate integration showcase.
 */
@Entity
@Table(name = "user_profiles")
public class UserProfile {
    @Id
    private Long id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    protected UserProfile() {
    }

    public UserProfile(Long id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }
}
