import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DesktopPreferences } from './desktop-preferences';
import { ScoreReadingPreferences } from './score-reading-preferences';
import { LOCAL_FETCH, SESSION_TOKEN } from './local-session';
import {
  isLocalAccount,
  LocalAccount,
  PROFILE_NAVIGATE,
  StudyProfiles,
  validProfileLaunch,
} from './study-profiles';

function accountFixture(): LocalAccount {
  return {
    accountId: '11111111-1111-4111-8111-111111111111',
    name: 'Conta local',
    activeProfileId: '22222222-2222-4222-8222-222222222222',
    openLastProfile: false,
    maxProfiles: 8,
    selectionRequired: true,
    profiles: [
      {
        id: '22222222-2222-4222-8222-222222222222',
        name: 'Meu estudo',
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

describe('local study profiles', () => {
  const request = vi.fn<typeof fetch>();
  const navigate = vi.fn<(uri: string) => void>();
  beforeEach(() => {
    localStorage.clear();
    request.mockReset();
    navigate.mockReset();
    TestBed.configureTestingModule({
      providers: [
        { provide: LOCAL_FETCH, useValue: request },
        { provide: SESSION_TOKEN, useValue: 'session' },
        { provide: PROFILE_NAVIGATE, useValue: navigate },
      ],
    });
  });
  afterEach(() => {
    TestBed.resetTestingModule();
    localStorage.clear();
  });

  it('validates identity, instruments, unique profiles and an unarchived active profile', () => {
    const account = accountFixture();
    expect(isLocalAccount(account)).toBe(true);
    expect(
      isLocalAccount({ ...account, profiles: [...account.profiles, ...account.profiles] }),
    ).toBe(false);
    expect(
      isLocalAccount({ ...account, profiles: [{ ...account.profiles[0], archived: true }] }),
    ).toBe(false);
    expect(
      isLocalAccount({
        ...account,
        profiles: [{ ...account.profiles[0], instrumentId: 'unknown' }],
      }),
    ).toBe(false);
    expect(isLocalAccount({ ...account, maxProfiles: '8' })).toBe(false);
    expect(
      isLocalAccount({ ...account, profiles: [{ ...account.profiles[0], name: '\u202Ehidden' }] }),
    ).toBe(false);
  });

  it('loads the authoritative limit and requires the selector unless explicit consent was saved', async () => {
    const account = { ...accountFixture(), maxProfiles: 3 };
    request.mockResolvedValueOnce(Response.json(account));
    const profiles = TestBed.inject(StudyProfiles);
    await profiles.load();
    expect(profiles.account()?.maxProfiles).toBe(3);
    expect(profiles.needsSelection()).toBe(true);
    expect(profiles.active()?.name).toBe('Meu estudo');
    expect(request.mock.calls[0]?.[1]?.headers).toMatchObject({ 'X-Arpeon-Session': 'session' });
  });

  it('supports legacy sessions only when the account endpoint is absent, never on denial', async () => {
    request.mockResolvedValueOnce(Response.json({}, { status: 401 }));
    const profiles = TestBed.inject(StudyProfiles);
    await profiles.load();
    expect(profiles.legacy()).toBe(false);
    expect(profiles.account()).toBeNull();
    expect(profiles.error()).toContain('conta local');
    request.mockResolvedValueOnce(Response.json({}, { status: 404 }));
    await profiles.load();
    expect(profiles.legacy()).toBe(true);
  });

  it('keeps metadata unchanged when saving fails and reflects backend archive errors', async () => {
    request.mockResolvedValueOnce(Response.json(accountFixture()));
    const profiles = TestBed.inject(StudyProfiles);
    await profiles.load();
    const original = profiles.account();
    request.mockResolvedValueOnce(
      Response.json({ error: 'Selecione outro perfil antes de arquivar este.' }, { status: 409 }),
    );
    expect(await profiles.archive(accountFixture().activeProfileId, true)).toBe(false);
    expect(profiles.account()).toEqual(original);
    expect(profiles.error()).toContain('Selecione outro perfil');
    expect(profiles.busy()).toBe(false);
  });

  it('navigates only to a validated new loopback session after the backend confirms selection', async () => {
    const uri = 'http://127.0.0.1:4317/#session=' + 's'.repeat(43);
    request.mockResolvedValueOnce(Response.json({ launchUri: uri }));
    const profiles = TestBed.inject(StudyProfiles);
    await profiles.select(accountFixture().activeProfileId);
    expect(navigate).toHaveBeenCalledExactlyOnceWith(uri);
    expect(profiles.busy()).toBe(true);
    expect(request.mock.calls[0]?.[1]?.body).toBe(
      JSON.stringify({ profileId: accountFixture().activeProfileId }),
    );
    for (const launchUri of [
      'https://example.com/',
      uri.replace('127.0.0.1', 'localhost'),
      uri.replace('/#', '/other#'),
      uri.replace('#', '?secret=x#'),
      'javascript:alert(1)',
    ])
      expect(validProfileLaunch({ launchUri })).toBe(false);
  });
  it('does not acknowledge stale success responses for profile creation or startup consent', async () => {
    const profiles = TestBed.inject(StudyProfiles);
    profiles.account.set(accountFixture());
    request.mockImplementation(async () => Response.json(accountFixture()));
    expect(
      await profiles.saveProfile({
        name: 'Piano',
        avatar: 'blue',
        instrumentId: 'piano',
        secondaryInstrumentIds: [],
      }),
    ).toBe(false);
    expect(await profiles.preferences('Conta local', true)).toBe(false);
    expect(profiles.account()?.openLastProfile).toBe(false);
    expect(profiles.account()?.profiles).toHaveLength(1);
  });

  it('keeps the selector usable after a denied switch and rejects untrusted redirect responses', async () => {
    const profiles = TestBed.inject(StudyProfiles);
    request.mockResolvedValueOnce(
      Response.json({ error: 'Salve o projeto antes de trocar.' }, { status: 409 }),
    );
    await profiles.select(accountFixture().activeProfileId);
    expect(profiles.error()).toContain('Salve o projeto');
    expect(profiles.busy()).toBe(false);
    request.mockResolvedValueOnce(Response.json({ launchUri: 'https://example.com/' }));
    await profiles.select(accountFixture().activeProfileId);
    expect(navigate).not.toHaveBeenCalled();
    expect(profiles.error()).toContain('não foi confirmada');
  });

  it('does not seed a managed profile from legacy browser storage and flushes pending reading values', async () => {
    localStorage.setItem('arpeon.theme.v1', 'light');
    localStorage.setItem('arpeon.layout.v1', '{"zoom":160,"readingEnabled":true}');
    const profiles = TestBed.inject(StudyProfiles);
    profiles.account.set({ ...accountFixture(), selectionRequired: false });
    request.mockImplementation(async (_path, init) =>
      Response.json(init?.method === 'PUT' ? JSON.parse(String(init.body)) : null),
    );
    const preferences = TestBed.inject(DesktopPreferences);
    await preferences.load();
    expect(preferences.snapshot().theme).toBe('dark');
    expect(preferences.snapshot().zoom).toBe(100);
    const reading = TestBed.inject(ScoreReadingPreferences);
    reading.readingLanguage.set('portuguese');
    reading.readingEnabled.set(true);
    await reading.flush();
    const body = JSON.parse(String(request.mock.lastCall?.[1]?.body)) as Record<string, unknown>;
    expect(body['readingEnabled']).toBe(true);
    expect(body['readingLanguage']).toBe('portuguese');
    expect(localStorage.getItem('arpeon.theme.v1')).toBe('light');
    expect(localStorage.getItem(profiles.cachePrefix() + 'layout.v1')).toContain('portuguese');
  });
});
