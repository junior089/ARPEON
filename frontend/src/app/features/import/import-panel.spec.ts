import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { ScoreSession } from '../score/score-session';
import { ImportPanel } from './import-panel';

const omrPreview = () => ({
  token: 'omr',
  title: 'Rascunho de validação',
  filename: 'Canção – flauta.pdf',
  sha256: 'a'.repeat(64),
  pages: 2,
  parts: 1,
  measures: 2,
  recognizedMeasures: [{ part: 'Flauta', number: '1', events: ['C5 · 1/4'] }],
  pagesRequiringReview: [1, 2],
  measuresRequiringReview: ['Flauta · 1'],
  warnings: ['P1 · compasso 1: Fórmula provisória 5/4. Confira o original.'],
  unsupportedSymbols: [],
});
async function panelWith(fetcher: typeof fetch) {
  await TestBed.configureTestingModule({
    imports: [ImportPanel],
    providers: [
      { provide: LOCAL_FETCH, useValue: fetcher },
      { provide: SESSION_TOKEN, useValue: 'session' },
      {
        provide: ScoreSession,
        useValue: { busy: signal(false), error: signal(''), projectDirty: signal(false) },
      },
    ],
  }).compileComponents();
  return TestBed.createComponent(ImportPanel);
}
function pdfEvent(name = 'Canção – flauta.pdf'): Event {
  const input = document.createElement('input');
  Object.defineProperty(input, 'files', {
    value: [new File(['%PDF-1.3'], name, { type: 'text/html' })],
  });
  return { target: input } as unknown as Event;
}

describe('ImportPanel', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });
  it('never requests an external import while provider capabilities are unavailable', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(Response.json({ error: 'Unavailable' }, { status: 500 }));
    const fixture = await panelWith(fetcher);
    const component = fixture.componentInstance;
    component.sourceUrl = 'https://commons.wikimedia.org/wiki/File:Example.mid';
    component.contact = 'user@example.org';
    await component.analyzeUrl();
    expect(component.error()).toContain('capacidades');
    expect(fetcher.mock.calls.some(([path]) => path === '/api/import/url')).toBe(false);
  });
  it('accepts accented filenames and shows locally rendered pages with disclosed draft cautions', async () => {
    const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:pdf-original');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    const fetcher = vi
      .fn<typeof fetch>()
      .mockImplementation(async (url) =>
        url === '/api/omr/pdf-page'
          ? new Response('png', { headers: { 'Content-Type': 'image/png' } })
          : Response.json(url === '/api/omr/status' ? { available: true } : omrPreview()),
      );
    const fixture = await panelWith(fetcher);
    await fixture.componentInstance.analyzeOmr(pdfEvent());
    await vi.waitFor(() => expect(fixture.componentInstance.omrPageLoading()).toBe(false));
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('.omr-pdf-page')?.getAttribute('src')).toBe('blob:pdf-original');
    expect(root.querySelector('iframe')).toBeNull();
    expect((create.mock.calls[0]?.[0] as Blob).type).toBe('application/pdf');
    expect(root.textContent).toContain('Rascunho · avisos de ritmo: 1');
    await fixture.componentInstance.showOmrOriginalPage(2);
    expect(fixture.componentInstance.omrOriginalPage()).toBe(2);
    expect(fetcher).toHaveBeenCalledWith(
      '/api/omr/pdf-page',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-Arpeon-Page': '2' }),
      }),
    );
    expect(fetcher).toHaveBeenCalledWith(
      '/api/omr/preview',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-Arpeon-Filename': encodeURIComponent('Canção – flauta.pdf'),
          'X-Arpeon-Job': expect.any(String),
        }),
      }),
    );
  });
  it('keeps a page error recoverable and ignores a page arriving after leaving the surface', async () => {
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:original');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    let finish: ((response: Response) => void) | undefined;
    let pageCalls = 0;
    const latePage = new Promise<Response>((resolve) => {
      finish = resolve;
    });
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (url) => {
      if (url === '/api/omr/status') return Response.json({ available: true });
      if (url === '/api/omr/cancel') return Response.json({ cancelled: true });
      if (url === '/api/omr/pdf-page') {
        if (++pageCalls === 1) return Response.json({ error: 'page failed' }, { status: 422 });
        return latePage;
      }
      return Response.json(omrPreview());
    });
    const fixture = await panelWith(fetcher);
    const panel = fixture.componentInstance;
    await panel.analyzeOmr(pdfEvent());
    await vi.waitFor(() => expect(panel.omrPageLoading()).toBe(false));
    expect(panel.omrPageError()).toContain('Tente novamente');
    expect(panel.omrPending()?.token).toBe('omr');
    const retry = panel.showOmrOriginalPage(1);
    await vi.waitFor(() => expect(pageCalls).toBe(2));
    panel.ngOnDestroy();
    finish?.(new Response('png', { headers: { 'Content-Type': 'image/png' } }));
    await retry;
    expect(panel.omrPageImage()).toBe('');
  });
  it('keeps native failure detail separate from the readable error', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockImplementation(async (url) =>
        url === '/api/omr/status'
          ? Response.json({ available: true })
          : Response.json(
              { error: 'O Audiveris não concluiu o reconhecimento.\nNATIVE_FAILURE_DETAIL' },
              { status: 422 },
            ),
      );
    const fixture = await panelWith(fetcher);
    await fixture.componentInstance.analyzeOmr(pdfEvent());
    expect(fixture.componentInstance.error()).toBe('O Audiveris não concluiu o reconhecimento.');
    expect(fixture.componentInstance.omrDiagnostic()).toBe('NATIVE_FAILURE_DETAIL');
  });
  it('cancels the matching backend job and discards a late successful response', async () => {
    let finish: ((response: Response) => void) | undefined;
    const previewResponse = new Promise<Response>((resolve) => {
      finish = resolve;
    });
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (url) => {
      if (url === '/api/omr/status') return Response.json({ available: true });
      if (url === '/api/omr/cancel') return Response.json({ cancelled: true });
      return previewResponse;
    });
    const fixture = await panelWith(fetcher);
    const panel = fixture.componentInstance;
    const running = panel.analyzeOmr(pdfEvent());
    await vi.waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith('/api/omr/preview', expect.anything()),
    );
    expect(panel.busy()).toBe(true);
    await panel.cancelOmr();
    expect(panel.busy()).toBe(true);
    const previewCall = fetcher.mock.calls.find((call) => call[0] === '/api/omr/preview');
    const cancelCall = fetcher.mock.calls.find((call) => call[0] === '/api/omr/cancel');
    const headers = previewCall?.[1]?.headers as Record<string, string>;
    expect(JSON.parse(String(cancelCall?.[1]?.body))).toEqual({ jobId: headers['X-Arpeon-Job'] });
    finish?.(Response.json(omrPreview()));
    await running;
    expect(panel.omrPending()).toBeNull();
    expect(panel.omrMessage()).toContain('Reconhecimento cancelado');
    expect(panel.busy()).toBe(false);
  });
  it('does not claim cancellation or lose the result if the cancellation request fails', async () => {
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:pdf-original');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    let finish: ((response: Response) => void) | undefined;
    const response = new Promise<Response>((resolve) => {
      finish = resolve;
    });
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (url) => {
      if (url === '/api/omr/status') return Response.json({ available: true });
      if (url === '/api/omr/cancel') throw new TypeError('connection failed');
      return response;
    });
    const fixture = await panelWith(fetcher);
    const panel = fixture.componentInstance;
    const running = panel.analyzeOmr(pdfEvent());
    await vi.waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith('/api/omr/preview', expect.anything()),
    );
    await panel.cancelOmr();
    expect(panel.cancellingOmr()).toBe(false);
    expect(panel.busy()).toBe(true);
    finish?.(Response.json(omrPreview()));
    await running;
    expect(panel.omrPending()?.token).toBe('omr');
  });
  it('does not republish a preview when cancellation completes during JSON decoding', async () => {
    let finish: ((value: unknown) => void) | undefined;
    const decoding = new Promise<unknown>((resolve) => {
      finish = resolve;
    });
    const response = Response.json({});
    const json = vi.spyOn(response, 'json').mockImplementation(() => decoding);
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (url) => {
      if (url === '/api/omr/status') return Response.json({ available: true });
      if (url === '/api/omr/cancel') return Response.json({ cancelled: true });
      return response;
    });
    const fixture = await panelWith(fetcher);
    const panel = fixture.componentInstance;
    const running = panel.analyzeOmr(pdfEvent());
    await vi.waitFor(() => expect(json).toHaveBeenCalled());
    await panel.cancelOmr();
    finish?.(omrPreview());
    await running;
    expect(panel.omrPending()).toBeNull();
    expect(panel.omrOriginalUrl()).toBe('');
    expect(panel.omrMessage()).toContain('Reconhecimento cancelado');
  });
  it('does not install a late result after leaving the import surface', async () => {
    let finish: ((response: Response) => void) | undefined;
    const response = new Promise<Response>((resolve) => {
      finish = resolve;
    });
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (url) => {
      if (url === '/api/omr/status') return Response.json({ available: true });
      if (url === '/api/omr/cancel') return Response.json({ cancelled: true });
      return response;
    });
    const fixture = await panelWith(fetcher);
    const panel = fixture.componentInstance;
    const running = panel.analyzeOmr(pdfEvent());
    await vi.waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith('/api/omr/preview', expect.anything()),
    );
    panel.ngOnDestroy();
    finish?.(Response.json(omrPreview()));
    await running;
    expect(panel.omrPending()).toBeNull();
    expect(fetcher).toHaveBeenCalledWith('/api/omr/cancel', expect.anything());
  });
  it('explains a lost local connection during PDF recognition', async () => {
    const fetcher = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch'));
    await TestBed.configureTestingModule({
      imports: [ImportPanel],
      providers: [
        { provide: LOCAL_FETCH, useValue: fetcher },
        { provide: SESSION_TOKEN, useValue: 'session' },
        { provide: ScoreSession, useValue: { busy: signal(false), error: signal('') } },
      ],
    }).compileComponents();
    const panel = TestBed.createComponent(ImportPanel).componentInstance;
    const input = document.createElement('input');
    Object.defineProperty(input, 'files', { value: [new File(['%PDF-1.3'], 'score.pdf')] });

    await panel.analyzeOmr({ target: input } as unknown as Event);

    expect(panel.error()).toContain('conexão com o aplicativo local caiu');
    expect(panel.error()).not.toContain('Failed to fetch');
  });

  it('reports missing Audiveris before uploading the PDF', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ available: false }));
    await TestBed.configureTestingModule({
      imports: [ImportPanel],
      providers: [
        { provide: LOCAL_FETCH, useValue: fetcher },
        { provide: SESSION_TOKEN, useValue: 'session' },
        { provide: ScoreSession, useValue: { busy: signal(false), error: signal('') } },
      ],
    }).compileComponents();
    const panel = TestBed.createComponent(ImportPanel).componentInstance;
    const input = document.createElement('input');
    Object.defineProperty(input, 'files', { value: [new File(['%PDF-1.3'], 'score.pdf')] });

    await panel.analyzeOmr({ target: input } as unknown as Event);

    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledWith('/api/omr/status', expect.any(Object));
    expect(panel.error()).toContain('Reinstale o pacote completo do ARPEON');
  });

  it('accepts and pages a local draft beyond the former 512-note limit', async () => {
    const notes = Array.from({ length: 513 }, () => ({
      step: 'C',
      accidental: 'NATURAL',
      octave: 4,
      onsetTicks: null,
      durationTicks: null,
      confidence: 1,
    }));
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({
        token: 'large-draft',
        draft: {
          kind: 'NOTES',
          filename: 'texto-colado.txt',
          sha256: 'a'.repeat(64),
          rhythm: 'NONE',
          notes,
          warnings: [],
        },
      }),
    );
    await TestBed.configureTestingModule({
      imports: [ImportPanel],
      providers: [
        { provide: LOCAL_FETCH, useValue: fetcher },
        { provide: SESSION_TOKEN, useValue: 'session' },
        { provide: ScoreSession, useValue: { busy: signal(false), error: signal('') } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ImportPanel);
    const panel = fixture.componentInstance;
    panel.notation = 'NOTES';
    panel.pasted = 'C4';
    await panel.analyzePaste();
    fixture.detectChanges();
    expect(panel.pending()?.draft.notes).toHaveLength(513);
    expect(panel.visibleDraftNotes()).toHaveLength(100);
    panel.draftPage.set(5);
    fixture.detectChanges();
    expect(panel.visibleDraftNotes()).toHaveLength(13);
  });

  it('requires an acknowledged OMR preview and keeps the original available for comparison', async () => {
    const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:original');
    const revokeUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(Response.json({ available: true }))
      .mockResolvedValueOnce(
        Response.json({
          token: 'omr-preview',
          title: 'Reconhecida',
          filename: 'sheet.png',
          sha256: 'a'.repeat(64),
          pages: 1,
          parts: 1,
          measures: 2,
          recognizedMeasures: [{ part: 'Flauta', number: '1', events: ['C5 · 1/4'] }],
          pagesRequiringReview: [1],
          measuresRequiringReview: ['Flauta · 1'],
          warnings: ['Revise o reconhecimento.'],
          unsupportedSymbols: [],
        }),
      );
    const acceptOmr = vi.fn().mockResolvedValue(true);
    await TestBed.configureTestingModule({
      imports: [ImportPanel],
      providers: [
        { provide: LOCAL_FETCH, useValue: fetcher },
        { provide: SESSION_TOKEN, useValue: 'session' },
        {
          provide: ScoreSession,
          useValue: {
            busy: signal(false),
            error: signal(''),
            projectDirty: signal(false),
            acceptOmr,
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ImportPanel);
    const panel = fixture.componentInstance;
    const file = new File([new Uint8Array([137, 80, 78, 71])], 'sheet.png', { type: 'image/png' });
    const input = document.createElement('input');
    Object.defineProperty(input, 'files', { value: [file] });
    await panel.analyzeOmr({ target: input } as unknown as Event);
    fixture.detectChanges();
    expect(panel.omrPending()?.pagesRequiringReview).toEqual([1]);
    expect((fixture.nativeElement as HTMLElement).querySelector('img')?.getAttribute('src')).toBe(
      'blob:original',
    );
    const original = (fixture.nativeElement as HTMLElement).querySelector('a[download]');
    expect(original?.getAttribute('href')).toBe('blob:original');
    expect(original?.getAttribute('download')).toBe('sheet.png');
    expect(original?.hasAttribute('target')).toBe(false);
    await panel.acceptOmr();
    expect(acceptOmr).not.toHaveBeenCalled();
    panel.omrAcknowledged = true;
    await panel.acceptOmr();
    expect(acceptOmr).toHaveBeenCalledWith('omr-preview');
    expect(revokeUrl).toHaveBeenCalledWith('blob:original');
    createUrl.mockRestore();
    revokeUrl.mockRestore();
  });

  it('shows Commons provenance and sends only the requested URL with API contact', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({
        token: 'web-preview',
        draft: {
          kind: 'MIDI',
          filename: 'Example.mid',
          sha256: 'b'.repeat(64),
          rhythm: 'EXACT_TICKS',
          warnings: ['Revise a grafia.'],
          notes: [
            {
              step: 'C',
              accidental: 'NATURAL',
              octave: 4,
              onsetTicks: 0,
              durationTicks: 480,
              confidence: 1,
            },
          ],
        },
        source: {
          providerId: 'wikimedia-commons',
          pageUrl: 'https://commons.wikimedia.org/wiki/File:Example.mid',
          fetchedAt: '2026-10-08T12:00:00Z',
          licenseStatus: 'DECLARED_REVIEW_REQUIRED',
          licenseShortName: 'CC0',
          licenseUrl: 'https://creativecommons.org/publicdomain/zero/1.0/',
          notes: ['Wikimedia Commons imageinfo API'],
        },
      }),
    );
    await TestBed.configureTestingModule({
      imports: [ImportPanel],
      providers: [
        { provide: LOCAL_FETCH, useValue: fetcher },
        { provide: SESSION_TOKEN, useValue: 'session' },
        { provide: ScoreSession, useValue: { busy: signal(false), error: signal('') } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ImportPanel);
    const panel = fixture.componentInstance;
    panel.sourceUrl = 'https://commons.wikimedia.org/wiki/File:Example.mid';
    panel.contact = 'user@example.org';
    fetcher.mockResolvedValueOnce(
      Response.json([
        {
          id: 'wikimedia-commons',
          name: 'Wikimedia Commons',
          host: 'commons.wikimedia.org',
          availabilityNote: 'MIDI selecionado',
          capabilities: {
            search: false,
            importByUrl: true,
            formats: ['MIDI'],
            selectParts: false,
            connectAccount: false,
          },
        },
      ]),
    );
    await panel.analyzeUrl();
    fixture.detectChanges();
    expect(fetcher).toHaveBeenCalledWith(
      '/api/import/url',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ url: panel.sourceUrl, contact: panel.contact }),
      }),
    );
    expect(panel.pending()?.source?.licenseShortName).toBe('CC0');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('CC0');
  });

  it('keeps pasted pitch sequence in preview until the user acknowledges the assumption', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({
        token: 'preview-1',
        draft: {
          kind: 'CIFRA_MELODICA',
          filename: 'texto-colado.txt',
          sha256: 'a'.repeat(64),
          rhythm: 'NONE',
          notes: [
            {
              step: 'C',
              accidental: 'NATURAL',
              octave: null,
              onsetTicks: null,
              durationTicks: null,
              confidence: 1,
            },
          ],
          warnings: ['A fonte não informa duração.'],
        },
      }),
    );
    const acceptImport = vi.fn().mockResolvedValue(true);
    await TestBed.configureTestingModule({
      imports: [ImportPanel],
      providers: [
        { provide: LOCAL_FETCH, useValue: fetcher },
        { provide: SESSION_TOKEN, useValue: 'session' },
        {
          provide: ScoreSession,
          useValue: {
            busy: signal(false),
            error: signal(''),
            projectDirty: signal(false),
            acceptImport,
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ImportPanel);
    const panel = fixture.componentInstance;
    panel.pasted = 'DO';
    await panel.analyzePaste();
    fixture.detectChanges();
    expect(panel.pending()?.draft.rhythm).toBe('NONE');
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('DO');
    const create = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((button) =>
      button.textContent?.includes('Criar rascunho'),
    );
    expect(create?.disabled).toBe(true);
    await panel.accept();
    expect(acceptImport).not.toHaveBeenCalled();
    panel.acknowledged = true;
    await panel.accept();
    expect(acceptImport).toHaveBeenCalledWith('preview-1', 4, 480);
    expect(panel.pending()).toBeNull();
  });
});
