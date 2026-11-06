import { Provider } from '@angular/core';
import { BrowserNavigation } from '../app/core/navigation/browser-navigation';
import { Notifier } from '../app/core/errors/notifier';

export interface NotifierStub {
  info: ReturnType<typeof vi.fn>;
  error: ReturnType<typeof vi.fn>;
}

export interface NavigationStub {
  assign: ReturnType<typeof vi.fn>;
}

export function notifierStub(): NotifierStub {
  return { info: vi.fn(), error: vi.fn() };
}

export function navigationStub(): NavigationStub {
  return { assign: vi.fn() };
}

export function stubProviders(notifier: NotifierStub, navigation: NavigationStub = navigationStub()): Provider[] {
  return [
    { provide: Notifier, useValue: notifier },
    { provide: BrowserNavigation, useValue: navigation },
  ];
}
