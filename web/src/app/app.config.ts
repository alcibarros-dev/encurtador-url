import { registerLocaleData } from '@angular/common';
import {
  HttpXsrfTokenExtractor,
  provideHttpClient,
  withFetch,
  withInterceptors,
  withXsrfConfiguration,
} from '@angular/common/http';
import localePt from '@angular/common/locales/pt';
import { ApplicationConfig, ErrorHandler, LOCALE_ID, provideBrowserGlobalErrorListeners } from '@angular/core';
import { MatPaginatorIntl } from '@angular/material/paginator';
import { TitleStrategy, provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { PageTitleStrategy } from './core/a11y/page-title.strategy';
import { paginatorIntl } from './core/a11y/paginator-intl';
import { GlobalErrorHandler } from './core/errors/global-error-handler';
import { sessionExpiryInterceptor } from './core/errors/session-expiry.interceptor';
import { BffXsrfTokenExtractor } from './core/errors/xsrf-token-extractor';

registerLocaleData(localePt);

/** Painel sem Zone.js, com estado em signals (ADR-0004, D3), só em português do Brasil (D10). */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(
      withFetch(),
      withInterceptors([sessionExpiryInterceptor]),
      withXsrfConfiguration({ headerName: 'X-XSRF-TOKEN' }),
    ),
    { provide: HttpXsrfTokenExtractor, useClass: BffXsrfTokenExtractor },
    { provide: TitleStrategy, useClass: PageTitleStrategy },
    { provide: ErrorHandler, useClass: GlobalErrorHandler },
    { provide: LOCALE_ID, useValue: 'pt-BR' },
    { provide: MatPaginatorIntl, useFactory: paginatorIntl },
  ],
};
