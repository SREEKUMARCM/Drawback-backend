package com.drawback.auth.repo;

import com.drawback.auth.domain.UserAccount;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<UserAccount, UUID> {

    @Query("select u from UserAccount u where lower(u.username) = lower(:identifier) or lower(u.email) = lower(:identifier)")
    Optional<UserAccount> findByIdentifier(String identifier);

    @Query("select count(u) > 0 from UserAccount u where lower(u.username) = lower(:username)")
    boolean usernameTaken(String username);

    @Query("select count(u) > 0 from UserAccount u where lower(u.email) = lower(:email)")
    boolean emailTaken(String email);

    List<UserAccount> findAllByIdIn(Collection<UUID> ids);

    @Query("select u from UserAccount u where lower(u.username) = lower(:username)")
    Optional<UserAccount> findByUsername(String username);

    @Query("select u from UserAccount u where u.phoneHash = :phoneHash")
    Optional<UserAccount> findByPhoneHash(String phoneHash);
}
