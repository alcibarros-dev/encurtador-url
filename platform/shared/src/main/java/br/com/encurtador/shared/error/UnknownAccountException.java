package br.com.encurtador.shared.error;

import org.springframework.http.HttpStatus;

/** 401 para um token válido cuja conta não existe mais: mesmo código de um token inválido (ADR-0001, D9). */
public class UnknownAccountException extends ApiException {

    public static final String CODE = "INVALID_TOKEN";

    public UnknownAccountException() {
        super(HttpStatus.UNAUTHORIZED, CODE, "A conta deste token não existe mais.");
    }
}
