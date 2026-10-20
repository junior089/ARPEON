import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LOCAL_FETCH, SESSION_TOKEN } from './local-session';
import { isSourceProviders, SourceCapabilities, SourceProvider } from './source-capabilities';

const provider: SourceProvider = {
  id: 'wikimedia-commons',
  name: 'Wikimedia Commons',
  host: 'commons.wikimedia.org',
  availabilityNote: 'MIDI selecionado com revisão.',
  capabilities: {
    search: false,
    importByUrl: true,
    formats: ['MIDI'],
    selectParts: false,
    connectAccount: false,
  },
};
describe('source capability contract', () => {
  afterEach(() => TestBed.resetTestingModule());
  it('rejects incomplete capabilities, duplicated ids and unavailable formats', () => {
    expect(isSourceProviders([provider])).toBe(true);
    for (const value of [
      [],
      [provider, provider],
      [{ ...provider, host: 'https://host' }],
      [{ ...provider, capabilities: { ...provider.capabilities, formats: [] } }],
      [{ ...provider, capabilities: { ...provider.capabilities, search: 'false' } }],
    ])
      expect(isSourceProviders(value)).toBe(false);
  });
  it('authenticates and caches verified capabilities and fails closed on reload failure', async () => {
    const request = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json([provider]))
      .mockResolvedValueOnce(Response.json({}, { status: 500 }))
      .mockResolvedValueOnce(Response.json([provider]));
    TestBed.configureTestingModule({
      providers: [
        { provide: LOCAL_FETCH, useValue: request },
        { provide: SESSION_TOKEN, useValue: 'private' },
      ],
    });
    const sources = TestBed.inject(SourceCapabilities);
    await Promise.all([sources.load(), sources.load()]);
    expect(request).toHaveBeenCalledTimes(1);
    expect(request).toHaveBeenCalledWith(
      '/api/import/providers',
      expect.objectContaining({ headers: { 'X-Arpeon-Session': 'private' }, cache: 'no-store' }),
    );
    expect(sources.canImportCommons()).toBe(true);
    await sources.load();
    expect(request).toHaveBeenCalledTimes(1);
    await sources.load(true);
    expect(sources.canImportCommons()).toBe(false);
    expect(sources.providers()).toBeNull();
    expect(sources.error()).toContain('Não foi possível');
    await sources.load();
    expect(sources.canImportCommons()).toBe(true);
  });
});
