import { computed, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { LOCAL_FETCH, SESSION_TOKEN } from './local-session';

export interface StudyProfile {
  id: string;
  name: string;
  avatar: 'amber' | 'sage' | 'blue' | 'rose';
  instrumentId: string;
  secondaryInstrumentIds: string[];
  archived: boolean;
}
export interface LocalAccount {
  accountId: string;
  name: string;
  activeProfileId: string;
  openLastProfile: boolean;
  maxProfiles: number;
  selectionRequired: boolean;
  profiles: StudyProfile[];
  instruments: { id: string; name: string }[];
}
export const PROFILE_NAVIGATE = new InjectionToken<(uri: string) => void>('profile navigation', {
  factory: () => (uri) => location.replace(uri),
});

const uuid = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
const record = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value);
const name = (value: unknown): value is string =>
  typeof value === 'string' &&
  value.trim().length > 0 &&
  Array.from(value).length <= 48 &&
  !/[\p{Cc}\p{Cf}]/u.test(value);

export function isLocalAccount(value: unknown): value is LocalAccount {
  if (
    !record(value) ||
    !uuid(value['accountId']) ||
    !name(value['name']) ||
    !uuid(value['activeProfileId']) ||
    typeof value['openLastProfile'] !== 'boolean' ||
    typeof value['selectionRequired'] !== 'boolean' ||
    !Number.isInteger(value['maxProfiles']) ||
    Number(value['maxProfiles']) < 1 ||
    Number(value['maxProfiles']) > 32 ||
    !Array.isArray(value['profiles']) ||
    value['profiles'].length < 1 ||
    value['profiles'].length > 32 ||
    !Array.isArray(value['instruments']) ||
    value['instruments'].length > 160
  )
    return false;
  const instruments = value['instruments'];
  if (
    !instruments.every(
      (item) =>
        record(item) &&
        typeof item['id'] === 'string' &&
        item['id'].length <= 64 &&
        typeof item['name'] === 'string' &&
        item['name'].length <= 100,
    )
  )
    return false;
  const ids = new Set(instruments.map((item) => (item as Record<string, unknown>)['id']));
  const profiles = value['profiles'];
  return (
    new Set(profiles.map((item) => (record(item) ? item['id'] : null))).size === profiles.length &&
    profiles.every(
      (item) =>
        record(item) &&
        uuid(item['id']) &&
        name(item['name']) &&
        ['amber', 'sage', 'blue', 'rose'].includes(String(item['avatar'])) &&
        ids.has(item['instrumentId']) &&
        typeof item['archived'] === 'boolean' &&
        Array.isArray(item['secondaryInstrumentIds']) &&
        item['secondaryInstrumentIds'].length <= 16 &&
        new Set(item['secondaryInstrumentIds']).size === item['secondaryInstrumentIds'].length &&
        item['secondaryInstrumentIds'].every((id) => ids.has(id) && id !== item['instrumentId']),
    ) &&
    profiles.some(
      (item) =>
        record(item) && item['id'] === value['activeProfileId'] && item['archived'] === false,
    )
  );
}

export function validProfileLaunch(value: unknown): value is { launchUri: string } {
  if (!record(value) || typeof value['launchUri'] !== 'string') return false;
  try {
    const uri = new URL(value['launchUri']);
    return (
      uri.protocol === 'http:' &&
      uri.hostname === '127.0.0.1' &&
      !!uri.port &&
      !uri.username &&
      !uri.password &&
      uri.pathname === '/' &&
      !uri.search &&
      /^#session=[A-Za-z0-9_-]{43}$/.test(uri.hash)
    );
  } catch {
    return false;
  }
}

@Injectable({ providedIn: 'root' })
export class StudyProfiles {
  private readonly fetch = inject(LOCAL_FETCH);
  private readonly token = inject(SESSION_TOKEN);
  private readonly navigate = inject(PROFILE_NAVIGATE);
  readonly account = signal<LocalAccount | null>(null);
  readonly legacy = signal(false);
  readonly error = signal('');
  readonly busy = signal(false);
  readonly choosing = signal(false);
  readonly active = computed(
    () =>
      this.account()?.profiles.find((profile) => profile.id === this.account()?.activeProfileId) ??
      null,
  );
  readonly needsSelection = computed(
    () => this.choosing() || this.account()?.selectionRequired === true,
  );

  cachePrefix(): string {
    const account = this.account();
    return account ? `arpeon.${account.accountId}.${account.activeProfileId}.` : 'arpeon.';
  }

  async load(): Promise<void> {
    this.error.set('');
    try {
      const response = await this.request('/api/account');
      if (response.status === 404) {
        this.legacy.set(true);
        return;
      }
      const data: unknown = await response.json();
      if (!response.ok || !isLocalAccount(data))
        throw new Error(
          'Não foi possível recuperar a conta local. Reinicie o aplicativo sem remover seus dados.',
        );
      this.account.set(data);
    } catch (failure) {
      this.fail(failure);
    }
  }

  async saveProfile(profile: Omit<StudyProfile, 'id' | 'archived'>, id?: string): Promise<boolean> {
    const previousIds = new Set(this.account()?.profiles.map((item) => item.id));
    return this.change(
      '/api/profiles',
      { ...profile, ...(id ? { profileId: id } : {}) },
      id ? 'PUT' : 'POST',
      (account) =>
        account.profiles.some(
          (item) =>
            (id ? item.id === id : !previousIds.has(item.id)) &&
            item.name === profile.name.normalize('NFC').trim() &&
            item.avatar === profile.avatar &&
            item.instrumentId === profile.instrumentId &&
            item.secondaryInstrumentIds.join(',') === profile.secondaryInstrumentIds.join(','),
        ),
    );
  }
  async archive(id: string, archived: boolean): Promise<boolean> {
    return this.change('/api/profiles/archive', { profileId: id, archived }, 'POST', (account) =>
      account.profiles.some((item) => item.id === id && item.archived === archived),
    );
  }
  async preferences(name: string, openLastProfile: boolean): Promise<boolean> {
    return this.change(
      '/api/account',
      { name, openLastProfile },
      'PUT',
      (account) =>
        account.name === name.normalize('NFC').trim() &&
        account.openLastProfile === openLastProfile,
    );
  }

  async select(id: string): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const response = await this.request('/api/profiles/select', { profileId: id });
      const data: unknown = await response.json();
      if (!response.ok) throw new Error(this.message(data));
      if (
        !validProfileLaunch(data) ||
        new URL(data.launchUri).origin === location.origin ||
        new URL(data.launchUri).hash === '#session=' + this.token
      )
        throw new Error('A sessão do perfil não foi confirmada. Reinicie o aplicativo.');
      this.navigate(data.launchUri);
    } catch (failure) {
      this.fail(failure);
      this.busy.set(false);
    }
  }

  private async change(
    path: string,
    body: object,
    method = 'POST',
    confirmed: (account: LocalAccount) => boolean = () => true,
  ): Promise<boolean> {
    if (this.busy()) return false;
    this.busy.set(true);
    this.error.set('');
    try {
      const response = await this.request(path, body, method);
      const data: unknown = await response.json();
      if (!response.ok) throw new Error(this.message(data));
      const previous = this.account();
      if (
        !isLocalAccount(data) ||
        (previous &&
          (data.accountId !== previous.accountId ||
            data.activeProfileId !== previous.activeProfileId)) ||
        !confirmed(data)
      )
        throw new Error('O registro do perfil não foi confirmado. Tente novamente.');
      this.account.set(data);
      return true;
    } catch (failure) {
      this.fail(failure);
      return false;
    } finally {
      this.busy.set(false);
    }
  }

  private request(path: string, body?: object, method = 'POST'): Promise<Response> {
    if (!this.token) return Promise.reject(new Error('Abra esta janela pelo aplicativo ARPEON.'));
    return this.fetch(path, {
      method: body ? method : 'GET',
      headers: {
        'X-Arpeon-Session': this.token,
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      cache: 'no-store',
      credentials: 'omit',
      signal: AbortSignal.timeout(10000),
    });
  }
  private message(value: unknown): string {
    return record(value) && typeof value['error'] === 'string'
      ? value['error']
      : 'Não foi possível atualizar o perfil. Tente novamente.';
  }
  private fail(failure: unknown): void {
    this.error.set(
      failure instanceof Error && !['TypeError', 'TimeoutError'].includes(failure.name)
        ? failure.message
        : 'A conexão local falhou. Tente novamente; seus dados permanecem no computador.',
    );
  }
}
