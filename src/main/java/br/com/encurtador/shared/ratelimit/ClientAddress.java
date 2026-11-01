package br.com.encurtador.shared.ratelimit;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;

/**
 * Sujeito dos limites por IP (ADR-0001, D11). IPv6 é agregado por /64: quem recebe um prefixo do provedor troca de
 * endereço dentro dele à vontade.
 * <p>
 * Vem de {@code getRemoteAddr()}. Atrás de proxy, use {@code server.forward-headers-strategy=native} com
 * {@code server.tomcat.remoteip.internal-proxies} restrito aos proxies confiáveis; nunca {@code framework}, que
 * confia no {@code X-Forwarded-For} de qualquer origem.
 */
final class ClientAddress {

    private ClientAddress() {
    }

    static String subjectOf(String remoteAddr) {
        try {
            InetAddress address = InetAddress.ofLiteral(remoteAddr);
            if (address instanceof Inet6Address) {
                return HexFormat.of().formatHex(address.getAddress(), 0, 8) + "/64";
            }
            return address.getHostAddress();
        } catch (IllegalArgumentException ex) {
            return remoteAddr; // ex.: IPv6 com nome de interface (fe80::1%en0)
        }
    }
}
