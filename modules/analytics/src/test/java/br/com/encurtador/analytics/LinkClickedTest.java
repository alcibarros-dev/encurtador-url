package br.com.encurtador.analytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Do Referer, só o host chega ao evento (ADR-0001, D8; RNF08). */
class LinkClickedTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            https://t.co/abc123                          | t.co
            HTTPS://WWW.Google.COM:443/search?q=segredo  | www.google.com
            http://[2001:db8::1]/pagina                  | [2001:db8::1]
            android-app://com.slack/                     | com.slack
            '  https://exemplo.com/  '                   | exemplo.com
            """)
    void keepsOnlyTheLowercaseHost(String referer, String expectedHost) {
        assertThat(LinkClicked.referrerHostOf(referer)).isEqualTo(expectedHost);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "about:blank", "/relativo", "não é uma url", "https://exemplo.com/a b"})
    void missingOrUnusableRefererBecomesDirectAccess(String referer) {
        assertThat(LinkClicked.referrerHostOf(referer)).isNull();
    }

    @Test
    void hostLongerThanTheColumnIsDropped() {
        String host = "a".repeat(250) + ".com.br";

        assertThat(LinkClicked.referrerHostOf("https://" + host + "/")).isNull();
    }

    @Test
    void redirectEventCarriesTheCurrentVersion() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-01T12:00:00Z");

        assertThat(LinkClicked.fromRedirect(eventId, 42, now, "https://t.co/x"))
                .isEqualTo(new LinkClicked(1, eventId, 42, now, "t.co"));
    }
}
