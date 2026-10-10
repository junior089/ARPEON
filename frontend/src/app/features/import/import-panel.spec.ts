import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { ScoreSession } from '../score/score-session';
import { ImportPanel } from './import-panel';

describe('ImportPanel', () => {
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
    expect(panel.error()).toContain('Audiveris não configurado');
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
