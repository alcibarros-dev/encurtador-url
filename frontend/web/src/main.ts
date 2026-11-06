import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

bootstrapApplication(App, appConfig).catch((error: unknown) =>
  console.error('Falha ao iniciar o painel', error instanceof Error ? error.name : error),
);
