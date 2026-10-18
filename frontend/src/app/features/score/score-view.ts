import { MeasureView, RehearsalView } from '../../core/score-contract';

export function scoreXmlForView(
  xml: string,
  activePartId: string,
  rehearsal: RehearsalView,
): string {
  if (!xml) return xml;
  const document = new DOMParser().parseFromString(xml, 'application/xml');
  for (const part of document.querySelectorAll('score-part, score-partwise > part')) {
    const id = part.getAttribute('id') ?? '';
    const visible =
      rehearsal.viewMode === 'full' ? !rehearsal.hiddenParts.includes(id) : id === activePartId;
    if (!visible) part.remove();
  }
  return new XMLSerializer().serializeToString(document);
}

export function performedMeasureForWritten(
  measures: MeasureView[],
  writtenIndex: number,
  currentIndex: number,
  loop?: { startMeasureIndex: number; endMeasureIndex: number } | null,
): MeasureView | undefined {
  const occurrences = measures.filter(
    (measure) =>
      (measure.writtenIndex ?? measure.index) === writtenIndex &&
      (!loop || (measure.index >= loop.startMeasureIndex && measure.index <= loop.endMeasureIndex)),
  );
  return occurrences.find((measure) => measure.index >= currentIndex) ?? occurrences[0];
}
