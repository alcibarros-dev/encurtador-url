import { TestBed } from '@angular/core/testing';
import { BffXsrfTokenExtractor } from './xsrf-token-extractor';

describe('BffXsrfTokenExtractor', () => {
  let extractor: BffXsrfTokenExtractor;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [BffXsrfTokenExtractor] });
    extractor = TestBed.inject(BffXsrfTokenExtractor);
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  it('lê o cookie XSRF-TOKEN do ambiente local', () => {
    document.cookie = 'outro=1; path=/';
    document.cookie = 'XSRF-TOKEN=abc%3D123; path=/';

    expect(extractor.getToken()).toBe('abc=123');
  });

  it('sem cookie, não há token', () => {
    expect(extractor.getToken()).toBeNull();
  });
});
