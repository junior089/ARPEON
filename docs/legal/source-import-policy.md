# Source import policy

Do not copy code without verifying its license and preserving required notices. Prefer
an original implementation based on public specifications. YIN's source is the published
algorithm citation in THIRD_PARTY_NOTICES; no third-party implementation was copied.

User-imported music stays separate from repository fixtures, public exports and training
datasets. Importing a file does not grant redistribution/training rights. No URL adapter or
scraper is implemented. MuseScore remains user-provided export/manual access; Cifra Melódica
remains manual/paste unless the provider authorizes specific automated access in the future.

Milestone 5 may add a provider adapter only after a documented, current review of its allowed
access mode, content rights, authentication and rate limits. A generic single-URL importer is
limited to sources where automation is permitted. Provider eligibility is checked before
fetching; a failed or unsupported source must not trigger scraping around access controls.
See the [roadmap addendum](../product/roadmap.md) for the planned import pipeline and tests.
