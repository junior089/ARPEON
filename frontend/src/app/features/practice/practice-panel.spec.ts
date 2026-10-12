import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PracticePanel } from './practice-panel';
import { PracticeSession } from './practice-session';
import { TunerSession } from '../tuner/tuner-session';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';

async function panel() {
  const session = {
    view: signal<{ attemptOpen: boolean } | null>(null),
    busy: signal(false),
    load: vi.fn().mockResolvedValue(null),
    generate: vi.fn().mockResolvedValue(false),
  };
  const microphone = { refreshDevices: vi.fn().mockResolvedValue(undefined) };
  await TestBed.configureTestingModule({ imports: [PracticePanel] })
    .overrideComponent(PracticePanel, {
      set: {
        template: '',
        providers: [
          { provide: PracticeSession, useValue: session },
          { provide: TunerSession, useValue: microphone },
        ],
      },
    })
    .compileComponents();
  const fixture = TestBed.createComponent(PracticePanel);
  fixture.detectChanges();
  await fixture.whenStable();
  return { component: fixture.componentInstance, session, fixture };
}
describe('PracticePanel preparation', () => {
  afterEach(() => TestBed.resetTestingModule());
  it('does not submit invalid or cleared input and recovers after correcting it', async () => {
    const { component, session } = await panel();
    component.bpm = NaN;
    await component.generate();
    expect(session.generate).not.toHaveBeenCalled();
    component.bpm = 80;
    component.progressiveStep = 21;
    await component.generate();
    expect(session.generate).not.toHaveBeenCalled();
    component.progressiveStep = 4;
    await component.generate();
    expect(session.generate).toHaveBeenCalledOnce();
  });
  it('submits advanced choices with the essential settings', async () => {
    const { component, session } = await panel();
    Object.assign(component, {
      kind: 'SCALE',
      keyId: 'D_MINOR',
      scaleType: 'MELODIC_MINOR',
      octave: 5,
      bpm: 96,
      loop: true,
      progressiveStep: 3,
    });
    await component.generate();
    expect(session.generate).toHaveBeenCalledWith({
      kind: 'SCALE',
      keyId: 'D_MINOR',
      scaleType: 'MELODIC_MINOR',
      octave: 5,
      bpm: 96,
      loop: true,
      progressiveStep: 3,
    });
  });
  it('does not replace an exercise while generating or while a attempt is open', async () => {
    const { component, session } = await panel();
    session.busy.set(true);
    await component.generate();
    session.busy.set(false);
    session.view.set({ attemptOpen: true });
    await component.generate();
    expect(session.generate).not.toHaveBeenCalled();
  });
  it('keeps a cleared numeric input invalid rather than converting it to zero', async () => {
    const { component } = await panel();
    const input = document.createElement('input');
    input.type = 'number';
    input.addEventListener(
      'input',
      (event) => (component.progressiveStep = component.number(event)),
    );
    input.dispatchEvent(new Event('input'));
    expect(Number.isNaN(component.progressiveStep)).toBe(true);
    expect(component.canGenerate()).toBe(false);
  });
  it('retains advanced and essential choices when the practice panel is recreated', async () => {
    const { component, fixture } = await panel();
    Object.assign(component, { octave: 5, bpm: 96, loop: true, progressiveStep: 3 });
    fixture.destroy();
    const next = TestBed.createComponent(PracticePanel);
    expect([
      next.componentInstance.octave,
      next.componentInstance.bpm,
      next.componentInstance.loop,
      next.componentInstance.progressiveStep,
    ]).toEqual([5, 96, true, 3]);
    next.destroy();
  });
  it('shows the actual minor scale selection after replacing the available scale options', async () => {
    await TestBed.configureTestingModule({
      imports: [PracticePanel],
      providers: [
        { provide: SESSION_TOKEN, useValue: 'session' },
        {
          provide: LOCAL_FETCH,
          useValue: vi.fn(async (input: string) => {
            if (input === '/api/practice') return new Response(null, { status: 409 });
            if (input.startsWith('/api/practice/history'))
              return Response.json({
                entries: [],
                page: 0,
                pageSize: 20,
                matchingCount: 0,
                totalCount: 0,
                completedCount: 0,
              });
            return Response.json(input === '/api/audio/preferences' ? null : []);
          }),
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(PracticePanel);
    fixture.detectChanges();
    await fixture.whenStable();
    const component = fixture.componentInstance;
    component.kind = 'SCALE';
    fixture.detectChanges();
    const selects = fixture.nativeElement.querySelectorAll(
      '.practice-fields select',
    ) as NodeListOf<HTMLSelectElement>;
    selects.item(1).value = 'D_MINOR';
    selects.item(1).dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const scale = fixture.nativeElement.querySelectorAll(
      '.practice-fields select',
    )[2] as HTMLSelectElement;
    expect(scale.value).toBe('NATURAL_MINOR');
    const generate = vi.spyOn(component.session, 'generate').mockResolvedValue(false);
    await component.generate();
    expect(generate).toHaveBeenCalledWith(
      expect.objectContaining({ keyId: 'D_MINOR', scaleType: scale.value }),
    );
    fixture.destroy();
  });
});
