export interface EditionEvidence {
  id: string;
  sourceAssetId: string;
  edition: { id: string; workId: string; title: string; externalIdentifiers: never[] } | null;
  citation: string;
  referenceUrl: string;
  recordedAt: string;
}
export interface EditionView {
  sourceAssetId: string;
  workId: string;
  history: EditionEvidence[];
  maxEntries: number;
}
const record = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value);
export const editionUuid = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
export const editionText = (value: unknown, max: number, required = false): value is string =>
  typeof value === 'string' &&
  value.length <= max &&
  (!required || !!value.trim()) &&
  !/[\p{Cc}\p{Cf}]/u.test(value);
export function editionReference(value: string): boolean {
  if (!value) return true;
  try {
    const authority = /^https:\/\/([^/?#]+)(?:\/[^?#]*)?$/.exec(value)?.[1];
    const url = new URL(value);
    return (
      !!authority &&
      !value.includes('\\') &&
      !/%(?![0-9a-f]{2})/i.test(value) &&
      (authority.startsWith('[') ? authority.endsWith(']') : !authority.includes(':')) &&
      url.protocol === 'https:' &&
      !!url.hostname &&
      !url.username &&
      !url.password &&
      !url.port &&
      !url.search &&
      !url.hash
    );
  } catch {
    return false;
  }
}
export function isEditionView(value: unknown, asset: string, workId: string): value is EditionView {
  if (
    !record(value) ||
    value['sourceAssetId'] !== asset ||
    value['workId'] !== workId ||
    value['maxEntries'] !== 16 ||
    !Array.isArray(value['history']) ||
    value['history'].length > 16
  )
    return false;
  const history = value['history'];
  return (
    new Set(history.map((entry) => (record(entry) ? entry['id'] : null))).size === history.length &&
    history.every((entry) => {
      if (
        !record(entry) ||
        !editionUuid(entry['id']) ||
        entry['sourceAssetId'] !== asset ||
        !editionText(entry['citation'], 1000, true) ||
        !editionText(entry['referenceUrl'], 1000) ||
        !editionReference(entry['referenceUrl']) ||
        typeof entry['recordedAt'] !== 'string' ||
        !Number.isFinite(Date.parse(entry['recordedAt']))
      )
        return false;
      const edition = entry['edition'];
      return (
        edition === null ||
        (record(edition) &&
          editionUuid(edition['id']) &&
          edition['workId'] === workId &&
          editionText(edition['title'], 200, true) &&
          Array.isArray(edition['externalIdentifiers']) &&
          edition['externalIdentifiers'].length === 0)
      );
    })
  );
}
