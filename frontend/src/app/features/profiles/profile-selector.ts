import { Component, computed, ElementRef, inject, output, signal, ViewChild } from '@angular/core';
import { StudyProfile, StudyProfiles } from '../../core/study-profiles';

@Component({
  selector: 'app-profile-selector',
  templateUrl: './profile-selector.html',
  styleUrl: './profile-selector.scss',
})
export class ProfileSelector {
  readonly profiles = inject(StudyProfiles);
  readonly returned = output<void>();
  readonly editing = signal<string | null>(null);
  readonly name = signal('');
  readonly avatar = signal<StudyProfile['avatar']>('amber');
  readonly instrument = signal('flute');
  readonly secondary = signal<string[]>([]);
  private nameInput?: ElementRef<HTMLInputElement>;
  @ViewChild('profileName') set profileNameInput(input: ElementRef<HTMLInputElement> | undefined) {
    this.nameInput = input;
    input?.nativeElement.focus();
  }
  readonly dirty = computed(() => {
    const id = this.editing();
    if (id === null) return false;
    const previous = this.profiles.account()?.profiles.find((profile) => profile.id === id);
    return (
      this.name() !== (previous?.name ?? '') ||
      this.avatar() !== (previous?.avatar ?? 'amber') ||
      this.instrument() !== (previous?.instrumentId ?? 'flute') ||
      this.secondary().join(',') !== (previous?.secondaryInstrumentIds.join(',') ?? '')
    );
  });
  readonly available = computed(
    () => this.profiles.account()?.profiles.filter((profile) => !profile.archived) ?? [],
  );
  readonly archived = computed(
    () => this.profiles.account()?.profiles.filter((profile) => profile.archived) ?? [],
  );
  readonly avatars: { id: StudyProfile['avatar']; name: string }[] = [
    { id: 'amber', name: 'Dourado' },
    { id: 'sage', name: 'Verde' },
    { id: 'blue', name: 'Azul' },
    { id: 'rose', name: 'Rosa' },
  ];

  edit(profile?: StudyProfile): void {
    if (!this.mayLeaveDraft()) return;
    this.editing.set(profile?.id ?? '');
    this.name.set(profile?.name ?? '');
    this.avatar.set(profile?.avatar ?? 'amber');
    this.instrument.set(profile?.instrumentId ?? 'flute');
    this.secondary.set(profile?.secondaryInstrumentIds ?? []);
    this.nameInput?.nativeElement.focus();
  }
  async choose(id: string): Promise<void> {
    if (this.mayLeaveDraft()) await this.profiles.select(id);
  }
  async startupChanged(event: Event): Promise<void> {
    const input = event.target;
    const account = this.profiles.account();
    if (!(input instanceof HTMLInputElement) || !account) return;
    await this.profiles.preferences(account.name, input.checked);
    input.checked = this.profiles.account()?.openLastProfile ?? false;
  }
  back(): void {
    if (this.mayLeaveDraft()) this.returned.emit();
  }
  private mayLeaveDraft(): boolean {
    return (
      !this.dirty() ||
      window.confirm('Há alterações do perfil não guardadas. Descartar e continuar?')
    );
  }
  setInstrument(id: string): void {
    this.instrument.set(id);
    this.secondary.update((items) => items.filter((item) => item !== id));
  }
  secondaryChanged(event: Event): void {
    if (event.target instanceof HTMLSelectElement)
      this.secondary.set(Array.from(event.target.selectedOptions).map((option) => option.value));
  }
  async save(event: Event): Promise<void> {
    event.preventDefault();
    const id = this.editing();
    if (id === null) return;
    if (
      await this.profiles.saveProfile(
        {
          name: this.name(),
          avatar: this.avatar(),
          instrumentId: this.instrument(),
          secondaryInstrumentIds: this.secondary(),
        },
        id || undefined,
      )
    )
      this.editing.set(null);
  }
  instrumentName(id: string): string {
    return this.profiles.account()?.instruments.find((item) => item.id === id)?.name ?? id;
  }
  initials(value: string): string {
    return value
      .trim()
      .split(/\s+/u)
      .slice(0, 2)
      .map((word) => Array.from(word)[0])
      .join('')
      .toLocaleUpperCase('pt-BR');
  }
  async archive(profile: StudyProfile): Promise<void> {
    if (window.confirm('Arquivar oculta este perfil na seleção e preserva seus dados. Continuar?'))
      await this.profiles.archive(profile.id, true);
  }
}
