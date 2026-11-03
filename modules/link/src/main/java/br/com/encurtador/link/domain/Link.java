package br.com.encurtador.link.domain;

import br.com.encurtador.link.LinkStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Link curto. A criação e a remoção são feitas por SQL ({@code ON CONFLICT} e {@code UPDATE} condicional, ver
 * {@code LinkWrites}); o JPA cuida das leituras. O dono é só um id, sem associação com {@code User} (ADR-0001, D6).
 */
@Entity
@Table(name = "links")
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = ShortCode.MAX_LENGTH)
    private String code;

    @Column(name = "target_url", nullable = false, length = TargetUrl.MAX_LENGTH)
    private String targetUrl;

    @Column(name = "owner_id", nullable = false)
    private long ownerId;

    @Column(name = "custom_alias", nullable = false)
    private boolean customAlias;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LinkStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Link() {
        // JPA
    }

    public long id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String targetUrl() {
        return targetUrl;
    }

    public LinkStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /** A expiração é irreversível: não há edição de link (D5). */
    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
