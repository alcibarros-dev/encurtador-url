package br.com.encurtador.identity.infra;

import br.com.encurtador.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, UserInsertion {

    /** Recebe o e-mail já normalizado ({@code EmailAddress.normalize}). */
    Optional<User> findByEmail(String email);

    @Modifying
    @Query("update User u set u.passwordHash = :passwordHash, u.updatedAt = :now where u.id = :id")
    int updatePasswordHash(long id, String passwordHash, Instant now);
}
