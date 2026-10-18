import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ScoreSession } from './score-session';
import { ScoreWorkspace } from './score-workspace';

async function emptyWorkspace(countInBeatsLeft = 0, fromReport = false) {
  await TestBed.configureTestingModule({
    imports: [ScoreWorkspace],
    providers: [
      {
        provide: ScoreSession,
        useValue: {
          rehearsal: signal({ viewMode: 'full', hiddenParts: [] }),
          score: signal(
            fromReport
              ? {
                  revision: 0,
                  keyId: null,
                  parts: [
                    {
                      id: 'P1',
                      name: 'Study',
                      measures: [
                        {
                          index: 0,
                          number: '1',
                          events: [
                            {
                              index: 0,
                              kind: 'NOTE',
                              pitch: 'C4',
                              step: 'C',
                              alter: 0,
                              octave: 4,
                              numerator: 1,
                              denominator: 4,
                            },
                            {
                              index: 1,
                              kind: 'NOTE',
                              pitch: 'D#4',
                              step: 'D',
                              alter: 1,
                              octave: 4,
                              numerator: 1,
                              denominator: 4,
                            },
                          ],
                        },
                      ],
                    },
                  ],
                }
              : null,
          ),
          transport: signal({
            tick: 0,
            status: countInBeatsLeft > 0 ? 'PLAYING' : 'STOPPED',
            bpm: 30,
            countInBeatsLeft,
            activePartId: 'P1',
          }),
          studySelection: signal(
            fromReport
              ? {
                  index: 1,
                  measureIndex: 0,
                  measureNumber: '1',
                  eventIndex: 1,
                  executionMeasureIndex: 0,
                  endMeasureIndex: 0,
                }
              : null,
          ),
          loadMeasureEvents: vi.fn().mockResolvedValue(true),
          busy: signal(false),
          xml: signal(''),
          reconnect: vi.fn().mockResolvedValue(false),
          refreshProjects: vi.fn(),
        },
      },
    ],
  })
    .overrideComponent(ScoreWorkspace, {
      set: {
        template:
          '<div #documentRegion class="test-region"><div class="page-caption"></div><div #sheet class="test-sheet"></div></div>',
      },
    })
    .compileComponents();
  const fixture = TestBed.createComponent(ScoreWorkspace);
  fixture.detectChanges();
  return fixture;
}

describe('ScoreWorkspace editor', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.clear();
  });

  it('keeps the note requested by an analysis report after asynchronous measure loading', async () => {
    const fixture = await emptyWorkspace(0, true);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.componentInstance.eventIndex()).toBe(1);
    expect(fixture.componentInstance.selectedEvent()?.pitch).toBe('D#4');
    expect(fixture.componentInstance.step).toBe('D');
    expect(fixture.componentInstance.alter).toBe(1);
    expect(fixture.componentInstance.session.studySelection()).toBeNull();
  });

  it('keeps the cursor hidden when zoom redraws notation during count-in', async () => {
    const fixture = await emptyWorkspace(2);
    const cursor = { hide: vi.fn(), show: vi.fn() };
    const renderer = { cursor, render: vi.fn(), clear: vi.fn() };
    (fixture.componentInstance as unknown as { renderer: unknown }).renderer = renderer;
    fixture.componentInstance.setZoom(125);
    fixture.detectChanges();
    expect(renderer.render).toHaveBeenCalled();
    expect(cursor.hide).toHaveBeenCalled();
    expect(cursor.show).not.toHaveBeenCalled();
  });

  it('opens the warned chord head in the correct voice instead of the same index in another voice', async () => {
    const fixture = await emptyWorkspace(0, true);
    await fixture.whenStable();
    const component = fixture.componentInstance;
    component.session.score.update((score) =>
      score
        ? {
            ...score,
            parts: score.parts.map((part) => ({
              ...part,
              measures: part.measures.map((measure) => ({
                ...measure,
                events: measure.events.map((event, index) =>
                  index === 1
                    ? {
                        ...event,
                        kind: 'CHORD',
                        voiceId: '2',
                        eventIndex: 0,
                        heads: [
                          { index: 0, pitch: 'D#4', tieStart: false, tieStop: false },
                          { index: 1, pitch: 'F#4', tieStart: false, tieStop: false },
                        ],
                      }
                    : { ...event, voiceId: '1', eventIndex: 0 },
                ),
              })),
            })),
          }
        : null,
    );
    await component.inspectRangeWarning({
      partId: 'P1',
      measureIndex: 0,
      voiceId: '2',
      eventIndex: 0,
      pitch: 'F#4',
      kind: 'OUTSIDE_RANGE',
    });
    expect(component.eventIndex()).toBe(1);
    expect(component.selectedHead()?.pitch).toBe('F#4');
    expect(component.warningPitch('F#4')).toBe('Fá♯4');
  });

  it('indicates the dominant visible page instead of a trailing fragment of the previous page', async () => {
    const fixture = await emptyWorkspace();
    const root: HTMLElement = fixture.nativeElement;
    const region = root.querySelector<HTMLElement>('.test-region');
    const caption = root.querySelector<HTMLElement>('.page-caption');
    const sheet = root.querySelector<HTMLElement>('.test-sheet');
    if (!region || !caption || !sheet) throw new Error('Missing score viewport');
    vi.spyOn(region, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 100, 800, 500));
    vi.spyOn(caption, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 100, 800, 40));
    const first = document.createElement('div');
    const second = document.createElement('div');
    for (const page of [first, second]) {
      page.append(document.createElementNS('http://www.w3.org/2000/svg', 'svg'));
      sheet.append(page);
    }
    vi.spyOn(first, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, -500, 800, 700));
    vi.spyOn(second, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 220, 800, 700));
    fixture.componentInstance.updateVisiblePage();
    expect(fixture.componentInstance.pageNumber()).toBe(2);
    vi.spyOn(first, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 180, 800, 700));
    vi.spyOn(second, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 900, 800, 700));
    fixture.componentInstance.updateVisiblePage();
    expect(fixture.componentInstance.pageNumber()).toBe(1);
  });

  it('selects a score event and sends an explicit pitch edit', async () => {
    const edit = vi.fn().mockResolvedValue(false);
    const events = [
      {
        index: 0,
        kind: 'CHORD',
        pitch: 'C4 + E4',
        step: 'C',
        alter: 0,
        octave: 4,
        numerator: 1,
        denominator: 4,
        tieStart: false,
        tieStop: false,
        heads: [
          { index: 0, pitch: 'C4', tieStart: false, tieStop: false },
          { index: 1, pitch: 'E4', tieStart: false, tieStop: false },
        ],
      },
      {
        index: 1,
        kind: 'NOTE',
        pitch: 'D4',
        step: 'D',
        alter: 0,
        octave: 4,
        numerator: 1,
        denominator: 4,
        tieStart: false,
        tieStop: false,
      },
      ...Array.from({ length: 50 }, (_, index) => ({
        index: index + 2,
        kind: 'NOTE',
        pitch: 'E4',
        step: 'E',
        alter: 0,
        octave: 4,
        numerator: 1,
        denominator: 4,
        tieStart: false,
        tieStop: false,
      })),
    ];
    await TestBed.configureTestingModule({
      imports: [ScoreWorkspace],
      providers: [
        {
          provide: ScoreSession,
          useValue: {
            rehearsal: signal({ viewMode: 'full', hiddenParts: [] }),
            score: signal({
              title: 'Test',
              filename: 'test.musicxml',
              sourceKind: 'MUSICXML',
              importWarnings: [],
              source: null,
              revision: 0,
              canUndo: false,
              canRedo: false,
              keyId: null,
              measures: [{ index: 0, number: '1' }],
              rangeWarnings: [],
              instruments: [{ id: 'concert', name: 'Som genérico' }],
              keys: [{ id: 'C_MAJOR', label: 'C maior' }],
              parts: [
                {
                  id: 'P1',
                  name: 'Melodia',
                  measures: [
                    {
                      index: 0,
                      number: '1',
                      events,
                    },
                  ],
                },
              ],
            }),
            transport: signal({
              tick: 0,
              activePartId: 'P1',
              parts: [{ id: 'P1', name: 'Melodia', instrumentId: 'flute' }],
            }),
            xml: signal(''),
            preview: signal(null),
            busy: signal(false),
            opening: signal(false),
            savingProject: signal(false),
            projectSaveError: signal(''),
            projects: signal([]),
            projectId: signal(null),
            projectDirty: signal(false),
            error: signal(''),
            refreshProjects: vi.fn().mockResolvedValue(undefined),
            loadMeasureEvents: vi.fn().mockResolvedValue(true),
            edit,
            action: vi.fn(),
            export: vi.fn(),
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ScoreWorkspace);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement as HTMLElement;
    expect(root.querySelector<HTMLSelectElement>('.zoom-control select')?.value).toBe('100');
    const duration = Array.from(root.querySelectorAll('label')).find((label) =>
      label.textContent?.trim().startsWith('Figura rítmica'),
    );
    expect(duration?.querySelector('select')?.value).toBe('4');
    const figureSelect = duration?.querySelector('select');
    if (!figureSelect) throw new Error('Missing rhythmic figure selector');
    figureSelect.value = '8';
    figureSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const dotsSelect = Array.from(root.querySelectorAll('label'))
      .find((label) => label.textContent?.trim().startsWith('Pontos de aumento'))
      ?.querySelector('select');
    if (!dotsSelect) throw new Error('Missing dots selector');
    dotsSelect.value = '1';
    dotsSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const changeDuration = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
      (button) => button.textContent?.trim() === 'Alterar duração',
    );
    changeDuration?.click();
    await fixture.whenStable();
    expect(edit).toHaveBeenCalledWith('set-duration', {
      partId: 'P1',
      measureIndex: 0,
      voiceId: '1',
      eventIndex: 0,
      numerator: 3,
      denominator: 16,
    });
    figureSelect.value = '1';
    figureSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(changeDuration?.disabled).toBe(true);
    expect(root.querySelector('.duration-warning')?.textContent).toContain('outra nota');
    fixture.componentInstance.selectEvent(0);
    fixture.detectChanges();
    const separator = root.querySelector<HTMLElement>('[role="separator"]');
    separator?.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    fixture.detectChanges();
    expect(separator?.getAttribute('aria-valuenow')).toBe('460');
    separator?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    fixture.detectChanges();
    expect(separator?.getAttribute('aria-valuenow')).toBe('280');
    const editor = root.querySelector<HTMLDetailsElement>('details.editor');
    expect(editor).not.toBeNull();
    if (editor) editor.open = true;
    const changePitch = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
      (button) => button.textContent?.trim() === 'Alterar nota',
    );
    expect(changePitch?.disabled).toBe(false);
    changePitch?.click();
    await fixture.whenStable();
    expect(edit).toHaveBeenCalledWith('set-pitch', {
      partId: 'P1',
      measureIndex: 0,
      voiceId: '1',
      eventIndex: 0,
      headIndex: 0,
      step: 'C',
      alter: 0,
      octave: 4,
    });
    const secondHead = Array.from(
      root.querySelectorAll<HTMLButtonElement>('.chord-heads button'),
    )[1];
    expect(secondHead?.textContent).toContain('E4');
    secondHead?.click();
    fixture.detectChanges();
    changePitch?.click();
    await fixture.whenStable();
    expect(edit).toHaveBeenLastCalledWith('set-pitch', {
      partId: 'P1',
      measureIndex: 0,
      voiceId: '1',
      eventIndex: 0,
      headIndex: 1,
      step: 'E',
      alter: 0,
      octave: 4,
    });
    const keyPreview = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
      (button) => button.textContent?.includes('Prévia para tom'),
    );
    expect(keyPreview?.disabled).toBe(true);
    let secondNote = root.querySelectorAll<HTMLButtonElement>('.study-notes button')[1];
    expect(secondNote).toBeDefined();
    expect(root.querySelectorAll('.study-notes button')).toHaveLength(48);
    const nextPage = Array.from(
      root.querySelectorAll<HTMLButtonElement>('.study-pagination button'),
    ).find((button) => button.textContent?.includes('Próxima'));
    nextPage?.click();
    fixture.detectChanges();
    expect(root.querySelector('.study-pagination')?.textContent).toContain('Página 2 de 2');
    expect(root.querySelectorAll('.study-notes button')).toHaveLength(4);
    expect(root.querySelector('.study-notes button .note-order')?.textContent).toBe('49');
    fixture.componentInstance.selectEvent(1);
    fixture.detectChanges();
    expect(root.querySelectorAll('.study-notes button')).toHaveLength(48);
    secondNote = root.querySelectorAll<HTMLButtonElement>('.study-notes button')[1];
    fixture.componentInstance.focusStudyNotes();
    expect(document.activeElement).toBe(root.querySelector('.study-notes button'));
    secondNote?.click();
    fixture.detectChanges();
    expect(root.querySelector('app-flute-fingering')?.textContent).toContain('Ré4');

    fixture.componentInstance.selectEvent(0);
    const graphicalMeasure = {};
    (fixture.componentInstance as unknown as { renderer: unknown }).renderer = {
      clear: vi.fn(),
      GraphicSheet: {
        MusicPages: [{}],
        domToSvg: (point: unknown) => point,
        svgToOsmd: (point: unknown) => point,
        GetNearestNote: () => ({
          sourceNote: {
            isRest: () => false,
            Pitch: { ToStringShort: () => 'D4' },
            ParentVoiceEntry: { ParentVoice: { Parent: { IdString: 'P1' }, VoiceId: 1 } },
          },
          PositionAndShape: { AbsolutePosition: { x: 0, y: 0 } },
          parentVoiceEntry: {
            parentStaffEntry: {
              parentMeasure: graphicalMeasure,
              relInMeasureTimestamp: { RealValue: 0.25 },
            },
          },
        }),
        MeasureList: [[graphicalMeasure]],
      },
    };
    const svgNote = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    Object.defineProperty(svgNote, 'getScreenCTM', {
      value: () => ({ inverse: () => ({}) }),
    });
    Object.defineProperty(svgNote, 'createSVGPoint', {
      value: () => ({ x: 0, y: 0, matrixTransform: () => ({ x: 0, y: 0 }) }),
    });
    const page = document.createElement('div');
    page.append(svgNote);
    root.querySelector('.notation')?.append(page);
    const scoreClick = new MouseEvent('click');
    Object.defineProperty(scoreClick, 'target', { value: svgNote });
    await fixture.componentInstance.selectRenderedNote(scoreClick);
    fixture.detectChanges();
    expect(root.querySelector('app-flute-fingering')?.textContent).toContain('Ré4');
  });
});
