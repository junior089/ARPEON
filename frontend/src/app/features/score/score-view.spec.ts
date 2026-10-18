import { describe, expect, it } from 'vitest';
import { scoreXmlForView, performedMeasureForWritten } from './score-view';

describe('score views', () => {
  const xml =
    '<score-partwise><part-list><score-part id="P1"><part-name>Flute</part-name></score-part><score-part id="P2"><part-name>Violin</part-name></score-part></part-list><part id="P1"><measure number="0" implicit="yes"><barline><repeat direction="forward"/></barline></measure></part><part id="P2"><measure number="0" implicit="yes"/></part></score-partwise>';

  it('filters individual notation while preserving IDs, pickup and repeat structure', () => {
    const filtered = scoreXmlForView(xml, 'P1', { viewMode: 'individual', hiddenParts: [] });
    const parsed = new DOMParser().parseFromString(filtered, 'application/xml');
    expect(parsed.querySelectorAll('score-part')).toHaveLength(1);
    expect(parsed.querySelector('part')?.id).toBe('P1');
    expect(parsed.querySelector('measure')?.getAttribute('implicit')).toBe('yes');
    expect(parsed.querySelector('repeat')?.getAttribute('direction')).toBe('forward');
    expect(xml).toContain('id="P2"');
  });

  it('full score visibility and rehearsal focus do not change canonical XML', () => {
    expect(
      new DOMParser()
        .parseFromString(
          scoreXmlForView(xml, 'P1', { viewMode: 'full', hiddenParts: ['P1'] }),
          'application/xml',
        )
        .querySelector('part')?.id,
    ).toBe('P2');
    expect(
      new DOMParser()
        .parseFromString(
          scoreXmlForView(xml, 'P1', { viewMode: 'focus', hiddenParts: ['P1'] }),
          'application/xml',
        )
        .querySelector('part')?.id,
    ).toBe('P1');
    expect(
      new DOMParser().parseFromString(xml, 'application/xml').querySelectorAll('part'),
    ).toHaveLength(2);
  });
  it('seeks repeated written measures by structural index rather than printed labels', () => {
    const measures = [0, 1, 0, 1].map((writtenIndex, index) => ({
      index,
      writtenIndex,
      number: 'same label',
      startTick: index * 1920,
      durationTicks: 1920,
    }));
    expect(performedMeasureForWritten(measures, 0, 2)?.startTick).toBe(3840);
    expect(performedMeasureForWritten(measures, 1, 2)?.startTick).toBe(5760);
    expect(
      performedMeasureForWritten(measures, 0, 3, { startMeasureIndex: 1, endMeasureIndex: 1 }),
    ).toBeUndefined();
  });
});
