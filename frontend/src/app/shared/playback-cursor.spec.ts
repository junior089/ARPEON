import type { Cursor } from 'opensheetmusicdisplay';
import { describe, expect, it, vi } from 'vitest';
import { PlaybackCursor } from './playback-cursor';

function repeatedScore(withInvisibleEvent = false) {
  const performed = withInvisibleEvent
    ? [0, 0.0625, 0.125, 0.25, 0.375, 0.5]
    : [0, 0.125, 0.25, 0.375, 0.5];
  const written = withInvisibleEvent
    ? [0, 0.0625, 0.125, 0, 0.125, 0.25]
    : [0, 0.125, 0, 0.125, 0.25];
  class Iterator {
    constructor(public index = 0) {}
    get EndReached() {
      return this.index >= performed.length;
    }
    get CurrentEnrolledTimestamp() {
      return { RealValue: performed[this.index] };
    }
    get CurrentSourceTimestamp() {
      return { RealValue: written[this.index] };
    }
    clone() {
      // Match OSMD: cloning uses the written position, not the performed pass.
      return new Iterator(written.indexOf(written[this.index] ?? 0));
    }
    moveToNext() {
      this.index++;
    }
    moveToNextVisibleVoiceEntry() {
      this.moveToNext();
      if (withInvisibleEvent && this.index === 1) this.moveToNext();
    }
  }
  const iterator = new Iterator();
  let hidden = true;
  const cursor = {
    iterator,
    get Hidden() {
      return hidden;
    },
    hide: vi.fn(() => {
      hidden = true;
    }),
    reset: vi.fn(() => {
      iterator.index = 0;
    }),
    next: vi.fn(() => iterator.moveToNextVisibleVoiceEntry()),
    show: vi.fn(() => {
      hidden = false;
    }),
    update: vi.fn(),
  };
  return { cursor, controller: new PlaybackCursor(cursor as unknown as Cursor) };
}

describe('PlaybackCursor', () => {
  it('uses the same visible events for lookahead and the drawn cursor', () => {
    const { controller, cursor } = repeatedScore(true);
    controller.position(120);
    expect(cursor.iterator.index).toBe(0);
    controller.position(240);
    expect(cursor.iterator.index).toBe(2);
    controller.position(479);
    expect(cursor.iterator.index).toBe(2);
    controller.position(480);
    expect(cursor.iterator.index).toBe(3);
  });
  it('does not redraw or restart traversal for clock corrections inside the same note', () => {
    const { controller, cursor } = repeatedScore();
    controller.position(240);
    controller.position(400);
    controller.position(390);
    controller.position(479);
    expect(cursor.iterator.index).toBe(1);
    expect(cursor.reset).toHaveBeenCalledTimes(1);
    expect(cursor.show).toHaveBeenCalledTimes(1);
    expect(cursor.update).toHaveBeenCalledTimes(1);
    expect(cursor.next).toHaveBeenCalledTimes(1);
  });

  it('shows the same note again after the cursor was hidden during count-in or a redraw', () => {
    const { controller, cursor } = repeatedScore();
    controller.position(0);
    cursor.hide();
    controller.position(0);
    expect(cursor.Hidden).toBe(false);
    expect(cursor.show).toHaveBeenCalledTimes(2);
    expect(cursor.reset).toHaveBeenCalledTimes(1);
  });
  it('keeps the sounding note until the next exact onset', () => {
    const { controller, cursor } = repeatedScore();
    controller.position(239);
    expect(cursor.iterator.index).toBe(0);
    controller.position(240);
    expect(cursor.iterator.index).toBe(1);
    controller.position(479);
    expect(cursor.iterator.index).toBe(1);
  });

  it('follows performed time when a repeat returns to an earlier written note', () => {
    const { controller, cursor } = repeatedScore();
    controller.position(480);
    expect(cursor.iterator.index).toBe(2);
    expect(cursor.iterator.CurrentSourceTimestamp.RealValue).toBe(0);
    controller.position(720);
    expect(cursor.iterator.index).toBe(3);
  });

  it('resets repeat traversal on backwards seeking and holds the last note at the end', () => {
    const { controller, cursor } = repeatedScore();
    controller.position(960);
    expect(cursor.iterator.index).toBe(4);
    controller.position(240);
    expect(cursor.iterator.index).toBe(1);
    controller.position(1920);
    expect(cursor.iterator.index).toBe(4);
    expect(cursor.show).toHaveBeenCalled();
  });

  it('traverses a real MusicXML repeat without advancing to a future note', async () => {
    // This test only parses the score and traverses its iterator; it does not draw a canvas.
    const canvas = vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
    const bundle = await import('opensheetmusicdisplay');
    const OSMD = (bundle.default ?? bundle).OpenSheetMusicDisplay;
    const renderer = new OSMD(document.createElement('div'), { autoResize: false });
    await renderer.load(`<?xml version="1.0" encoding="UTF-8"?>
      <score-partwise version="4.0"><part-list><score-part id="P1">
        <part-name>Flute</part-name></score-part></part-list><part id="P1">
        <measure number="1"><attributes><divisions>1</divisions>
          <time><beats>1</beats><beat-type>4</beat-type></time>
          <clef><sign>G</sign><line>2</line></clef></attributes>
          <barline location="left"><repeat direction="forward"/></barline>
          <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration><type>quarter</type></note>
        </measure><measure number="2">
          <note><pitch><step>D</step><octave>4</octave></pitch><duration>1</duration><type>quarter</type></note>
          <barline location="right"><repeat direction="backward"/></barline>
        </measure></part></score-partwise>`);
    const manager = renderer.Sheet.MusicPartManager;
    let hidden = true;
    const cursor = {
      iterator: manager.getIterator(),
      get Hidden() {
        return hidden;
      },
      hide() {
        hidden = true;
      },
      reset() {
        this.iterator = manager.getIterator();
      },
      next() {
        this.iterator.moveToNextVisibleVoiceEntry(false);
      },
      show: vi.fn(() => {
        hidden = false;
      }),
      update: vi.fn(),
    };
    const controller = new PlaybackCursor(cursor as unknown as Cursor);
    controller.position(960);
    expect(cursor.iterator.CurrentSourceTimestamp.RealValue).toBe(0);
    expect(cursor.iterator.CurrentEnrolledTimestamp.RealValue).toBe(0.5);
    controller.position(1439);
    expect(cursor.iterator.CurrentSourceTimestamp.RealValue).toBe(0);
    controller.position(1440);
    expect(cursor.iterator.CurrentSourceTimestamp.RealValue).toBe(0.25);
    controller.position(0);
    expect(cursor.iterator.CurrentEnrolledTimestamp.RealValue).toBe(0);
    renderer.clear();
    canvas.mockRestore();
  });
});
