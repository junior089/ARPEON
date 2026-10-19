import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ScoreSession } from './score-session';
import { PartImportPanel } from './part-import-panel';

describe('part import review', () => {
  it('requires review of incompatible sources and sends explicit anchors for late entries', async () => {
    const edit = vi.fn().mockResolvedValue(true);
    const previewAlignment = vi.fn().mockResolvedValue({ valid: true, parts: [] });
    await TestBed.configureTestingModule({
      imports: [PartImportPanel],
      providers: [
        {
          provide: ScoreSession,
          useValue: {
            score: signal({ parts: [{ id: 'P1', name: 'Flauta', measures: [{}, {}, {}] }] }),
            busy: signal(false),
            edit,
            previewAlignment,
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(PartImportPanel);
    const panel = fixture.componentInstance;
    panel.review.set({
      token: 'source-token',
      filename: 'violin.musicxml',
      parts: [
        {
          id: 'source',
          name: 'Violino',
          instrumentId: 'violin',
          measures: 2,
          compatible: false,
          problem: 'Extensão diferente',
        },
      ],
    });
    panel.selected.set(['source']);
    fixture.detectChanges();
    expect(panel.canConfirm()).toBe(false);
    panel.manual.set(true);
    panel.anchors.set([{ sourceMeasure: 0, compositionMeasure: 1 }]);
    await panel.checkAlignment();
    expect(panel.canConfirm()).toBe(true);
    previewAlignment.mockResolvedValueOnce({ valid: false, parts: [] });
    await panel.checkAlignment();
    expect(panel.canConfirm()).toBe(false);
    await panel.checkAlignment();
    await panel.confirm();
    expect(edit).toHaveBeenCalledWith('commit-import', {
      token: 'source-token',
      partIds: '["source"]',
      replaceId: '',
      manual: true,
      anchors: '[{"sourceMeasure":0,"compositionMeasure":1}]',
    });
    expect(panel.review()).toBeNull();
  });
});
