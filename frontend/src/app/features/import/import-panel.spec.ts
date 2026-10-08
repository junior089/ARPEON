import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { ScoreSession } from '../score/score-session';
import { ImportPanel } from './import-panel';

describe('ImportPanel', () => {
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
          useValue: { busy: signal(false), error: signal(''), acceptImport },
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
