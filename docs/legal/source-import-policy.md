# Source import policy

Do not copy code without verifying its license and preserving required notices. Prefer
an original implementation based on public specifications. YIN's source is the published
algorithm citation in THIRD_PARTY_NOTICES; no third-party implementation was copied.

User-imported music stays separate from repository fixtures, public exports and training
datasets. Importing a file does not grant redistribution/training rights. One narrow Commons
API adapter is implemented; there is no generic scraper. MuseScore remains user-provided
export/manual access; Cifra Melódica remains manual/paste unless the provider authorizes
specific automated access in the future.

The reviewed Mutopia catalogue adds one selected MIDI movement, with a pinned
archive/member hash and documented Public Domain declaration. It accepts an
allowlisted selection ID, not an arbitrary URL. Its exact member bytes are
retained after acceptance; the downloaded ZIP is only a bounded public memory
cache. See [ADR 0013](../adr/0013-reviewed-mutopia-midi-selection.md).

Milestone 5 may add a provider adapter only after a documented, current review of its allowed
access mode, content rights, authentication and rate limits. A generic single-URL importer is
limited to sources where automation is permitted. Provider eligibility is checked before
fetching; a failed or unsupported source must not trigger scraping around access controls.
See the [roadmap addendum](../product/roadmap.md) for the planned import pipeline and tests.
The current [provider registry review](provider-registry.md) records each access mode and
the primary policy sources used for the Commons adapter.
