package br.com.encurtador.link.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tabela de hosts do D10, regra 7, com as bordas de cada faixa bloqueada. Recebe hosts já normalizados. */
class HostClassifierTest {

    private final HostClassifier hosts = new HostClassifier("Encurta.Dor.", List.of("Login.Exemplo.NET"));

    @ParameterizedTest
    @ValueSource(strings = {
            "exemplo.com", "sub.exemplo.com.br", "xn--caf-dma.com", "localhost.com", "notlocalhost", "meuencurta.dor",
            "8.8.8.8", "1.1.1.1", "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255",
            "128.0.0.0", "169.253.255.255", "169.255.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255",
            "192.169.0.0", "198.17.255.255", "198.20.0.0", "223.255.255.255",
            "[2001:4860:4860::8888]", "[2001:db8::1]", "[fec0::1]", "[::ffff:8.8.8.8]", "[::8.8.8.8]",
            "[64:ff9b::808:808]"})
    void acceptsPublicHosts(String host) {
        assertThatCode(() -> hosts.check(host)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "0.255.255.255", "10.0.0.0", "10.255.255.255", "100.64.0.0", "100.127.255.255", "127.0.0.1",
            "127.255.255.255", "169.254.0.0", "169.254.169.254", "172.16.0.0", "172.31.255.255", "192.168.0.1",
            "198.18.0.0", "198.19.255.255", "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255",
            "[::]", "[::1]", "[fe80::1]", "[febf::1]", "[fc00::1]", "[fdff::1]", "[ff02::1]", "[::ffff:10.0.0.1]",
            "[::ffff:7f00:1]", "[::127.0.0.1]", "[64:ff9b::7f00:1]", "[64:ff9b::a9fe:a9fe]"})
    void rejectsPrivateLocalAndReservedAddresses(String host) {
        assertThatThrownBy(() -> hosts.check(host)).hasMessageContaining("IP privado, local ou reservado");
    }

    /** Formas que os navegadores leem como IPv4 (regra "ends in a number" do WHATWG). */
    @ParameterizedTest
    @ValueSource(strings = {"2130706433", "0x7f000001", "0x7f.1", "0177.0.0.1", "127.1", "1.2.3.04", "256.1.1.1",
            "1.2.3.4.5", "exemplo.0x1", "exemplo.123", "0x"})
    void rejectsNonCanonicalNumericHosts(String host) {
        assertThatThrownBy(() -> hosts.check(host)).hasMessageContaining("forma canônica");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "painel.localhost"})
    void rejectsLocalhost(String host) {
        assertThatThrownBy(() -> hosts.check(host)).hasMessage("não pode apontar para localhost");
    }

    @ParameterizedTest
    @ValueSource(strings = {"encurta.dor", "www.encurta.dor", "a.b.encurta.dor"})
    void rejectsTheShortenerItselfAndItsSubdomains(String host) {
        assertThatThrownBy(() -> hosts.check(host)).hasMessage("não pode apontar para o próprio encurtador");
    }

    @ParameterizedTest
    @ValueSource(strings = {"login.exemplo.net", "a.login.exemplo.net"})
    void rejectsOtherServicesOfTheShortenerAndTheirSubdomains(String host) {
        assertThatThrownBy(() -> hosts.check(host)).hasMessage("não pode apontar para um serviço do encurtador");
    }

    @ParameterizedTest
    @ValueSource(strings = {"exemplo.net", "outrologin.exemplo.net"})
    void acceptsNeighboursOfTheServiceHosts(String host) {
        assertThatCode(() -> hosts.check(host)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[fe80::1%eth0]", "[fe80::1%25eth0]"})
    void rejectsIpv6ZoneIds(String host) {
        assertThatThrownBy(() -> hosts.check(host)).hasMessage("não pode ter identificador de zona IPv6");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[1:2:3:4:5:6:7:8:9]", "[::g]"})
    void rejectsMalformedIpv6(String host) {
        assertThatThrownBy(() -> hosts.check(host)).hasMessage("tem um endereço IPv6 inválido");
    }
}
