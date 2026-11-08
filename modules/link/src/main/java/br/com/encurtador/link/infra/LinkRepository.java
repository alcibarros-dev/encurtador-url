package br.com.encurtador.link.infra;

import br.com.encurtador.link.LinkStatus;
import br.com.encurtador.link.domain.Link;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Leituras por JPA e escritas por SQL ({@link LinkWrites}). Estende {@link Repository}, e não {@code JpaRepository},
 * para não oferecer {@code save()}: a criação é sempre por {@code INSERT … ON CONFLICT} (ADR-0001, D3).
 */
public interface LinkRepository extends Repository<Link, Long>, LinkWrites {

    /** Toda consulta da API filtra pelo dono: link de outro usuário é indistinguível de link inexistente (D9). */
    Optional<Link> findByCodeAndOwnerIdAndStatus(String code, long ownerId, LinkStatus status);

    Page<Link> findByOwnerIdAndStatus(long ownerId, LinkStatus status, Pageable pageable);

    List<Link> findByOwnerIdAndStatus(long ownerId, LinkStatus status);
}
