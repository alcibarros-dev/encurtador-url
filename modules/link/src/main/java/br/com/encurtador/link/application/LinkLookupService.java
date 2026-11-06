package br.com.encurtador.link.application;

import br.com.encurtador.link.LinkLookup;
import br.com.encurtador.link.LinkSnapshot;
import br.com.encurtador.link.LinkStatus;
import br.com.encurtador.link.domain.Link;
import br.com.encurtador.link.domain.ShortCode;
import br.com.encurtador.link.infra.LinkRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
class LinkLookupService implements LinkLookup {

    private final LinkRepository links;

    LinkLookupService(LinkRepository links) {
        this.links = links;
    }

    @Override
    public Optional<LinkSnapshot> findByCode(String code) {
        if (!ShortCode.isWellFormed(code)) {
            return Optional.empty();
        }
        return links.findByCode(code).map(LinkLookupService::snapshot);
    }

    @Override
    public Optional<LinkSnapshot> findOwned(String code, long ownerId) {
        if (!ShortCode.isWellFormed(code)) {
            return Optional.empty();
        }
        return links.findByCodeAndOwnerIdAndStatus(code, ownerId, LinkStatus.ACTIVE).map(LinkLookupService::snapshot);
    }

    @Override
    public List<LinkSnapshot> ownedLinks(long ownerId) {
        return links.findByOwnerIdAndStatus(ownerId, LinkStatus.ACTIVE).stream()
                .map(LinkLookupService::snapshot)
                .toList();
    }

    private static LinkSnapshot snapshot(Link link) {
        return new LinkSnapshot(link.id(), link.code(), link.targetUrl(), link.status(), link.expiresAt());
    }
}
