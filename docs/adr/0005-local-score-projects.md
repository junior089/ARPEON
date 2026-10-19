# ADR 0005 — Local score projects

Status: accepted for the first local persistence implementation.

Evolution: [ADR 0010](0010-local-account-profile-sessions.md) scopes projects by
study profile; [ADR 0011](0011-initial-source-evidence.md) adds schema 3 and retained
originals for new direct score imports. The non-retention decision below records
the initial implementation; microphone audio remains excluded.

## Decision

The desktop application stores named score projects under
`Path.of(System.getProperty("user.home"), ".arpeon", "projects")`. Project IDs are
random UUIDs; request data never supplies a filesystem path. Each project directory
contains a versioned `manifest.json` and canonical `score.musicxml`. The manifest
stores the source filename and SHA-256, parser version, source kind, import warnings,
and reviewed Wikimedia provenance when present. A separate checksum detects changes
to the stored MusicXML.

Saving writes a sibling staging directory, moves the previous directory to a
recoverable backup, then moves the complete staging directory into place. If the
second move fails, the previous directory is restored. The format is versioned so
future migrations can be explicit. The first version persists the current score and
its provenance; the in-memory undo/redo stack and active transport position do not
survive restart.

Projects are local and never contain microphone audio or original imported bytes.
MusicXML remains independently exportable. Loading parses and validates the saved
MusicXML, timeline, export and MIDI state before replacing the active workspace.
The project listing, save, load and delete operations use the existing authenticated
loopback API and same-origin checks.

## Consequences

- A user can reopen an edited score after closing ARPEON, and an explicitly saved
  project is updated automatically after later accepted score edits.
- Filesystem persistence stays in `apps/desktop`; musical domain modules remain
  independent of storage.
- Projects are application data rather than portable bundles in this first version.
  A future `.arpeon` export/import container needs its own size and integrity rules.
- Analysis reports are not included in this first project schema. A later change can
  add bounded, separate report records linked to project ID and score revision.

## Alternatives considered

- Persist every source file: rejected because it duplicates potentially large or
  copyrighted material and can include audio.
- Persist only MusicXML: rejected because source provenance and import warnings would
  be lost when reopening a project.
- Put persistence into `arpeon-domain`: rejected because it would couple musical
  invariants to filesystem and serialization concerns.
