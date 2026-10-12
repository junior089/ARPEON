import type { Cursor } from 'opensheetmusicdisplay';

export class PlaybackCursor {
  private tick = -1;
  private next: Cursor['iterator'] | undefined;

  constructor(private readonly cursor: Cursor) {}

  position(tick: number): void {
    if (tick === this.tick && !this.cursor.Hidden) return;
    const time = tick / 1920;
    // Poll corrections within the same note do not require replaying the entire score.
    let changed = this.tick < 0 || time < this.cursor.iterator.CurrentEnrolledTimestamp.RealValue;
    if (changed) {
      this.cursor.hide();
      this.cursor.reset();
      // OSMD clone() starts from written time and does not retain repeat traversal.
      // Clone only at the beginning, then keep the lookahead iterator in lockstep.
      this.next = this.cursor.iterator.clone();
      this.next.SkipInvisibleNotes = this.cursor.iterator.SkipInvisibleNotes;
      this.next.moveToNextVisibleVoiceEntry(false);
    }
    changed ||= this.cursor.Hidden;
    let steps = 0;
    while (
      this.next &&
      !this.next.EndReached &&
      this.next.CurrentEnrolledTimestamp.RealValue <= time &&
      steps++ < 100_000
    ) {
      if (!changed) {
        this.cursor.hide();
        changed = true;
      }
      this.cursor.next();
      this.next.moveToNextVisibleVoiceEntry(false);
    }
    if (changed && !this.cursor.iterator.EndReached) {
      this.cursor.show();
      // OSMD attempts to scroll before show() restores the image's display style.
      // Update once after it is visible so following a note also follows its page.
      this.cursor.update();
    }
    this.tick = tick;
  }
}
