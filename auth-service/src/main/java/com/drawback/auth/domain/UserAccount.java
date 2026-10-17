package com.drawback.auth.domain;

import com.drawback.security.Role;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "user_account")
public class UserAccount {

    public enum Status { ACTIVE, SUSPENDED }

    @Id
    private UUID id;
    @Column(nullable = false)
    private String username;
    @Column(nullable = false)
    private String email;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Column(name = "display_name", nullable = false)
    private String displayName;
    private boolean verified;
    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Version
    private Long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Set<Role> roles = EnumSet.of(Role.USER);

    protected UserAccount() {}

    public UserAccount(String username, String email, String passwordHash, String displayName) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.email = email.toLowerCase();
        this.passwordHash = passwordHash;
        this.displayName = displayName;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = EnumSet.copyOf(roles); }
    public boolean isActive() { return status == Status.ACTIVE; }
}
