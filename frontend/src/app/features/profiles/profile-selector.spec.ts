import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { StudyProfiles, LocalAccount } from '../../core/study-profiles';
import { LOCAL_FETCH, SESSION_TOKEN } from '../../core/local-session';
import { ProfileSelector } from './profile-selector';

function fixtureAccount(): LocalAccount {
  return {
    accountId: '11111111-1111-4111-8111-111111111111',
    name: 'Estudo',
    activeProfileId: '22222222-2222-4222-8222-222222222222',
    openLastProfile: false,
    maxProfiles: 2,
    selectionRequired: true,
    profiles: [
      {
        id: '22222222-2222-4222-8222-222222222222',
        name: 'Flauta',
        avatar: 'amber',
        instrumentId: 'flute',
        secondaryInstrumentIds: [],
        archived: false,
      },
    ],
    instruments: [
      { id: 'flute', name: 'Flauta transversal' },
      { id: 'piano', name: 'Piano' },
    ],
  };
}
describe('profile selector', () => {
  afterEach(() => TestBed.resetTestingModule());
  async function open(account = fixtureAccount()) {
    const request = vi.fn<typeof fetch>().mockResolvedValue(Response.json(account));
    await TestBed.configureTestingModule({
      imports: [ProfileSelector],
      providers: [
        { provide: LOCAL_FETCH, useValue: request },
        { provide: SESSION_TOKEN, useValue: 'session' },
      ],
    }).compileComponents();
    TestBed.inject(StudyProfiles).account.set(account);
    const fixture = TestBed.createComponent(ProfileSelector);
    fixture.detectChanges();
    return { fixture, request, element: fixture.nativeElement as HTMLElement };
  }
  it('shows actual profiles, instrument names and the backend quota', async () => {
    const { element } = await open();
    expect(element.textContent).toContain('1 de 2');
    expect(element.querySelector('[aria-label="Estudar com Flauta"]')).not.toBeNull();
    expect(element.textContent).toContain('Flauta transversal');
    const activeArchive = Array.from(element.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Arquivar'),
    );
    expect(activeArchive?.disabled).toBe(true);
  });
  it('creates with a named instrument and keeps a failed edit open for correction', async () => {
    const { fixture, request } = await open();
    const panel = fixture.componentInstance;
    panel.edit();
    panel.name.set('Piano');
    panel.setInstrument('piano');
    panel.secondary.set(['flute']);
    request.mockResolvedValueOnce(Response.json({ error: 'Disco indisponível' }, { status: 500 }));
    await panel.save(new Event('submit'));
    fixture.detectChanges();
    expect(panel.editing()).toBe('');
    expect(panel.profiles.error()).toBe('Disco indisponível');
    expect(request.mock.lastCall?.[1]?.body).toBe(
      JSON.stringify({
        name: 'Piano',
        avatar: 'amber',
        instrumentId: 'piano',
        secondaryInstrumentIds: ['flute'],
      }),
    );
    const saved = fixtureAccount();
    saved.profiles.push({
      id: '33333333-3333-4333-8333-333333333333',
      name: 'Piano',
      avatar: 'amber',
      instrumentId: 'piano',
      secondaryInstrumentIds: ['flute'],
      archived: false,
    });
    request.mockResolvedValueOnce(Response.json(saved));
    await panel.save(new Event('submit'));
    expect(panel.editing()).toBeNull();
  });
  it('removes a new primary instrument from secondary instruments', async () => {
    const { fixture } = await open();
    const panel = fixture.componentInstance;
    panel.secondary.set(['piano']);
    panel.setInstrument('piano');
    expect(panel.secondary()).toEqual([]);
  });
  it('shows the stored primary instrument when the edit form is first rendered', async () => {
    const account = fixtureAccount();
    const initial = account.profiles[0];
    if (!initial) throw new Error('Expected initial profile');
    account.profiles[0] = {
      ...initial,
      instrumentId: 'piano',
      secondaryInstrumentIds: ['flute'],
    };
    const { fixture, element } = await open(account);
    fixture.componentInstance.edit(account.profiles[0]);
    fixture.detectChanges();
    expect(element.querySelector<HTMLSelectElement>('#profile-instrument')?.value).toBe('piano');
    expect(
      Array.from(
        element.querySelector<HTMLSelectElement>('#profile-secondary')?.selectedOptions ?? [],
      ).map((option) => option.value),
    ).toEqual(['flute']);
  });
  it('preserves a changed draft when leaving is declined', async () => {
    const { fixture } = await open();
    const panel = fixture.componentInstance;
    panel.edit();
    panel.name.set('Estudo de piano');
    const confirmation = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const select = vi.spyOn(panel.profiles, 'select');
    try {
      await panel.choose(fixtureAccount().activeProfileId);
      panel.edit(fixtureAccount().profiles[0]);
      expect(select).not.toHaveBeenCalled();
      expect(panel.name()).toBe('Estudo de piano');
      expect(panel.editing()).toBe('');
    } finally {
      confirmation.mockRestore();
    }
  });
  it('disables creation at the configured quota and leaves archived data restorable', async () => {
    const account = fixtureAccount();
    account.maxProfiles = 1;
    const { element } = await open(account);
    const create = Array.from(element.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Criar perfil',
    );
    expect(create?.disabled).toBe(true);
    expect(element.textContent).toContain('Limite de perfis atingido');
  });
  it('restores the confirmed startup consent when its disk write fails', async () => {
    const { fixture, request, element } = await open();
    request.mockResolvedValueOnce(Response.json({ error: 'Disco indisponível' }, { status: 500 }));
    const input = element.querySelector<HTMLInputElement>('.resume-option input');
    if (!input) throw new Error('Expected startup consent input');
    input.checked = true;
    const event = new Event('change');
    Object.defineProperty(event, 'target', { value: input });
    await fixture.componentInstance.startupChanged(event);
    expect(input.checked).toBe(false);
    expect(fixture.componentInstance.profiles.account()?.openLastProfile).toBe(false);
    expect(fixture.componentInstance.profiles.error()).toBe('Disco indisponível');
  });
});
