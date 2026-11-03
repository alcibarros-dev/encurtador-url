package br.com.encurtador.link.domain;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Classifica o host de um destino sem consultar DNS (ADR-0001, D10, regra 7). Recusa endereços que apontam para a
 * rede de quem clica e para o próprio encurtador.
 * <p>
 * É higiene, não barreira: um nome DNS pode resolver para 192.168.0.1. Também não há SSRF a evitar, porque o servidor
 * nunca acessa o destino.
 */
public final class HostClassifier {

    /** Quatro octetos decimais sem zero à esquerda: a única forma de IPv4 que todos os clientes leem igual. */
    private static final Pattern CANONICAL_IPV4 =
            Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

    private static final List<Ipv4Range> BLOCKED_IPV4 = List.of(
            new Ipv4Range(0, 0, 0, 0, 8),        // "esta rede"
            new Ipv4Range(10, 0, 0, 0, 8),       // privada
            new Ipv4Range(100, 64, 0, 0, 10),    // NAT de operadora
            new Ipv4Range(127, 0, 0, 0, 8),      // loopback
            new Ipv4Range(169, 254, 0, 0, 16),   // link-local
            new Ipv4Range(172, 16, 0, 0, 12),    // privada
            new Ipv4Range(192, 168, 0, 0, 16),   // privada
            new Ipv4Range(198, 18, 0, 0, 15),    // testes de desempenho
            new Ipv4Range(224, 0, 0, 0, 4),      // multicast
            new Ipv4Range(240, 0, 0, 0, 4));     // reservada, inclui o broadcast

    private final String shortenerHost;

    /** @param shortenerHost host de {@code app.base-url}; ele e seus subdomínios são recusados (evita loops) */
    public HostClassifier(String shortenerHost) {
        this.shortenerHost = normalize(shortenerHost);
    }

    /** Minúsculas e sem um ponto final: {@code Exemplo.COM.} e {@code exemplo.com} são o mesmo host. */
    public static String normalize(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    /**
     * @param host host já normalizado, com IPv6 entre colchetes (como o {@code java.net.URI} devolve)
     * @throws InvalidValueException se o host não pode ser destino de um link
     */
    public void check(String host) {
        if (host.startsWith("[")) {
            checkIpv6(host.substring(1, host.length() - 1));
        } else if (endsInNumber(host)) {
            checkIpv4(host);
        } else {
            checkName(host);
        }
    }

    /**
     * Regra "ends in a number" do WHATWG: os navegadores leem {@code 2130706433}, {@code 0x7f.1} e {@code 0177.0.0.1}
     * como 127.0.0.1. Todo host cujo último rótulo é numérico ou começa com {@code 0x} é tratado como IPv4.
     */
    private static boolean endsInNumber(String host) {
        String lastLabel = host.substring(host.lastIndexOf('.') + 1);
        return lastLabel.startsWith("0x") || (!lastLabel.isEmpty() && lastLabel.chars().allMatch(Character::isDigit));
    }

    private static void checkIpv4(String host) {
        if (!CANONICAL_IPV4.matcher(host).matches()) {
            throw new InvalidValueException("tem um IPv4 fora da forma canônica (quatro números de 0 a 255, como "
                    + "203.0.113.10)");
        }
        String[] octets = host.split("\\.");
        int address = 0;
        for (String octet : octets) {
            address = (address << 8) | Integer.parseInt(octet);
        }
        rejectIfBlocked(address);
    }

    private static void checkIpv6(String literal) {
        if (literal.indexOf('%') >= 0) {
            throw new InvalidValueException("não pode ter identificador de zona IPv6");
        }
        byte[] address;
        try {
            address = InetAddress.ofLiteral(literal).getAddress();
        } catch (IllegalArgumentException ex) {
            throw new InvalidValueException("tem um endereço IPv6 inválido");
        }
        if (address.length == 4) {
            // O JDK devolve o IPv4 de um endereço mapeado (::ffff:a.b.c.d).
            rejectIfBlocked(toInt(address, 0));
            return;
        }
        int first = address[0] & 0xff;
        int second = address[1] & 0xff;
        boolean multicast = first == 0xff;                               // ff00::/8
        boolean linkLocal = first == 0xfe && (second & 0xc0) == 0x80;    // fe80::/10
        boolean uniqueLocal = (first & 0xfe) == 0xfc;                    // fc00::/7
        if (multicast || linkLocal || uniqueLocal) {
            throw blockedAddress();
        }
        // IPv4 compatível (::a.b.c.d, que inclui :: e ::1) ou NAT64 (64:ff9b::a.b.c.d): vale a regra do IPv4 embutido.
        boolean compatible = isZero(address, 0, 12);
        boolean nat64 = first == 0x00 && second == 0x64 && (address[2] & 0xff) == 0xff && (address[3] & 0xff) == 0x9b
                && isZero(address, 4, 12);
        if (compatible || nat64) {
            rejectIfBlocked(toInt(address, 12));
        }
    }

    private void checkName(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            throw new InvalidValueException("não pode apontar para localhost");
        }
        if (host.equals(shortenerHost) || host.endsWith("." + shortenerHost)) {
            throw new InvalidValueException("não pode apontar para o próprio encurtador");
        }
    }

    private static void rejectIfBlocked(int ipv4) {
        if (BLOCKED_IPV4.stream().anyMatch(range -> range.contains(ipv4))) {
            throw blockedAddress();
        }
    }

    private static InvalidValueException blockedAddress() {
        return new InvalidValueException("não pode apontar para um endereço IP privado, local ou reservado");
    }

    private static boolean isZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static int toInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private record Ipv4Range(int network, int prefixLength) {

        Ipv4Range(int a, int b, int c, int d, int prefixLength) {
            this((a << 24) | (b << 16) | (c << 8) | d, prefixLength);
        }

        boolean contains(int address) {
            return ((address ^ network) >>> (32 - prefixLength)) == 0;
        }
    }
}
