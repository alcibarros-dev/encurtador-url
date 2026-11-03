package br.com.encurtador.ratelimit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            203.0.113.10                             | 203.0.113.10
            2001:db8:1:2:aaaa:bbbb:cccc:dddd         | 20010db800010002/64
            2001:db8:1:2::1                          | 20010db800010002/64
            0:0:0:0:0:0:0:1                          | 0000000000000000/64
            ::ffff:203.0.113.10                      | 203.0.113.10
            nao-e-um-ip                              | nao-e-um-ip
            """)
    void ipv6IsAggregatedPer64(String remoteAddr, String subject) {
        assertThat(ClientAddress.subjectOf(remoteAddr)).isEqualTo(subject);
    }
}
