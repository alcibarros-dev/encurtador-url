package br.com.encurtador.redirect.application;

import br.com.encurtador.analytics.ClickRecorder;
import br.com.encurtador.analytics.LinkClicked;
import br.com.encurtador.shared.time.UuidV7Generator;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Entrega o clique ao analytics sem esperar por ele (ADR-0001, §2.1, passo 5). */
@Component
public class ClickReporter {

    private final ClickRecorder recorder;
    private final UuidV7Generator ids;
    private final Clock clock;

    public ClickReporter(ClickRecorder recorder, UuidV7Generator ids, Clock clock) {
        this.recorder = recorder;
        this.ids = ids;
        this.clock = clock;
    }

    /** O instante do clique é o do redirecionamento, não o do consumo (corrige P4). */
    public void report(long linkId, @Nullable String refererHeader) {
        recorder.offer(LinkClicked.fromRedirect(ids.next(), linkId, clock.instant(), refererHeader));
    }
}
