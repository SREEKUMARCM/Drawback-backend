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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "user_account")
public class UserAccount {

    public enum Status { ACTIVE, SUSPENDED, DELETED }

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

    @Column(nullable = false)
    private String bio = "";
    private String location;
    /** Comma-separated category slugs (slugs never contain commas). */
    @Column(nullable = false)
    private String interests = "";
    @Column(name = "avatar_url")
    private String avatarUrl;
    @Column(nullable = false)
    private boolean onboarded;
    @Column(name = "phone_hash")
    private String phoneHash;
    @Column(name = "phone_masked")
    private String phoneMasked;

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
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = EnumSet.copyOf(roles); }
    public boolean isActive() { return status == Status.ACTIVE; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio == null ? "" : bio; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public List<String> getInterests() {
        return interests.isBlank() ? List.of() : Arrays.asList(interests.split(","));
    }
    public void setInterests(List<String> slugs) { this.interests = slugs == null ? "" : String.join(",", slugs); }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public boolean isOnboarded() { return onboarded; }
    public void setOnboarded(boolean onboarded) { this.onboarded = onboarded; }
    public String getPhoneHash() { return phoneHash; }
    public String getPhoneMasked() { return phoneMasked; }

    /** Marks the account as phone-verified. Only the hash and a masked label are kept, never the number. */
    public void linkPhone(String phoneHash, String phoneMasked) {
        this.phoneHash = phoneHash;
        this.phoneMasked = phoneMasked;
        this.verified = true;
    }

    /** Account deletion: keep the row (content elsewhere references the id) but remove everything personal. */
    public void anonymise(String randomPasswordHash) {
        String shortId = id.toString().substring(0, 8);
        this.username = "deleted_" + shortId;
        this.email = "deleted+" + id + "@invalid.local";
        this.passwordHash = randomPasswordHash;
        this.displayName = "Deleted user";
        this.bio = "";
        this.location = null;
        this.interests = "";
        this.avatarUrl = null;
        this.phoneHash = null;
        this.phoneMasked = null;
        this.verified = false;
        this.status = Status.DELETED;
        this.roles = EnumSet.of(Role.USER);
    }
}
