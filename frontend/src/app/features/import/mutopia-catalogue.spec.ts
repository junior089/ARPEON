import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { SourceCapabilities } from '../../core/source-capabilities';
import { CatalogueEntry, isMutopiaCatalogue, MutopiaCatalogue } from './mutopia-catalogue';

const entry: CatalogueEntry = {
  id: 'bach-bwv1013-allemande-1528',
  title: 'Allemande — Partita em lá menor, BWV 1013',
  composer: 'Johann Sebastian Bach',
  instrument: 'Flauta solo',
  pageUrl: 'https://www.mutopiaproject.org/cgibin/piece-info.cgi?id=1528',
  sourceRevision: '2144afd6f52d56c5b6995b8b589ef1268b3139f0',
  sourceUrl:
    'https://github.com/MutopiaProject/MutopiaProject/blob/2144afd6f52d56c5b6995b8b589ef1268b3139f0/ftp/BachJS/BWV1013/bwv1013/bwv1013.ly',
  license: 'Public Domain',
  licenseUrl: 'https://creativecommons.org/licenses/publicdomain/',
  credit: 'Mutopia Project · Jacob Shapiro · Mutopia-2017/10/31-1528',
  filename: 'bwv1013.mid',
  sha256: '43016ce88c25e691143d392bf31e6efd12b478ebe03ff378396a4eed97af8702',
  byteSize: 6011,
};
describe('reviewed Mutopia catalogue', () => {
  afterEach(() => TestBed.resetTestingModule());
  it('rejects ambiguous entries, unsafe links, missing revision and invalid file evidence', () => {
    expect(isMutopiaCatalogue([entry])).toBe(true);
    for (const value of [
      [],
      [entry, entry],
      [{ ...entry, pageUrl: 'javascript:alert(1)' }],
      [{ ...entry, sourceUrl: 'https://example.org/file' }],
      [{ ...entry, sourceRevision: 'main' }],
      [{ ...entry, filename: '../file.mid' }],
      [{ ...entry, sha256: 'bad' }],
      [{ ...entry, byteSize: 0 }],
    ])
      expect(isMutopiaCatalogue(value)).toBe(false);
  });
  it('loads only the local authenticated list and emits the reviewed entry after selection', async () => {
    const request = vi.fn<typeof fetch>().mockResolvedValue(Response.json([entry]));
    await TestBed.configureTestingModule({
      imports: [MutopiaCatalogue],
      providers: [
        { provide: LOCAL_FETCH, useValue: request },
        { provide: SESSION_TOKEN, useValue: 'private' },
        {
          provide: SourceCapabilities,
          useValue: {
            load: vi.fn().mockResolvedValue(undefined),
            canImportMutopia: () => true,
            error: () => '',
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(MutopiaCatalogue);
    fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    fixture.detectChanges();
    const selected = vi.fn();
    fixture.componentInstance.selected.subscribe(selected);
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('Não é uma busca em todo o acervo');
    expect(root.textContent).toContain('Jacob Shapiro');
    expect(request).toHaveBeenCalledWith(
      '/api/import/catalogue/mutopia',
      expect.objectContaining({ headers: { 'X-Arpeon-Session': 'private' }, cache: 'no-store' }),
    );
    expect(request).toHaveBeenCalledTimes(1);
    root.querySelector<HTMLButtonElement>('.primary')?.click();
    expect(selected).toHaveBeenCalledWith(entry);
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();
    expect(root.querySelector<HTMLButtonElement>('.primary')?.disabled).toBe(true);
  });
  it('fails closed on unavailable capabilities and recovers after a deliberate retry', async () => {
    let enabled = false;
    const request = vi.fn<typeof fetch>().mockResolvedValue(Response.json([entry]));
    await TestBed.configureTestingModule({
      imports: [MutopiaCatalogue],
      providers: [
        { provide: LOCAL_FETCH, useValue: request },
        { provide: SESSION_TOKEN, useValue: 'private' },
        {
          provide: SourceCapabilities,
          useValue: {
            load: vi.fn().mockResolvedValue(undefined),
            canImportMutopia: () => enabled,
            error: () => '',
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(MutopiaCatalogue);
    fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    expect(request).not.toHaveBeenCalled();
    expect(fixture.componentInstance.error()).toContain('não está disponível');
    enabled = true;
    await fixture.componentInstance.load();
    expect(fixture.componentInstance.entries()).toEqual([entry]);
    expect(fixture.componentInstance.error()).toBe('');
    const signal = request.mock.calls[0]?.[1]?.signal;
    fixture.destroy();
    expect(signal?.aborted).toBe(true);
  });
});
