package br.com.encurtador.support;

import br.com.encurtador.link.application.ShortCodeGenerator;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Devolve primeiro os códigos programados e depois delega ao gerador real. Permite forçar colisões com códigos já
 * gravados; quem programa precisa chamar {@link #reset()} ao terminar.
 */
public final class ScriptedShortCodeGenerator implements ShortCodeGenerator {

    private final ShortCodeGenerator delegate;
    private final Queue<String> scripted = new ConcurrentLinkedQueue<>();

    ScriptedShortCodeGenerator(ShortCodeGenerator delegate) {
        this.delegate = delegate;
    }

    public void willReturn(String code, int times) {
        for (int i = 0; i < times; i++) {
            scripted.add(code);
        }
    }

    public void reset() {
        scripted.clear();
    }

    @Override
    public String next() {
        String code = scripted.poll();
        return code != null ? code : delegate.next();
    }
}
