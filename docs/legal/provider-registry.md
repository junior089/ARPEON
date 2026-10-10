# Web source access review — 2026-10-08

ARPEON checks an exact provider hostname and access mode **before** any network request.
Unknown hosts and generic HTML are disabled until their access policy has been reviewed.
No login, browser cookie, paywall, anti-bot bypass, crawl, or bulk download is used.

| Provider | Access mode | Authentication | Automation | Rate and status | Rights |
| --- | --- | --- | --- | --- | --- |
| Wikimedia Commons | Official MediaWiki Action API | None for public file metadata | Yes, one user-selected `File:` MIDI page | One attempt per two seconds per app instance; `429`/`503` pause; implemented | File-specific; license metadata is shown for review, not treated as blanket permission |
| MuseScore.com | User-provided export | Depends on user access | Disabled | Manual only | User supplies a lawful export |
| Cifra Melódica | User paste | None in ARPEON | Disabled | Manual only | User pastes notes obtained legitimately |

The Commons adapter calls `action=query&prop=imageinfo` for the requested file and downloads
only the returned original MIDI from the approved Wikimedia upload host. It validates API MIME,
media URL, size, SHA-1 and MIDI header, then passes the bytes to Milestone 4's bounded MIDI
parser. It displays file-page URL, retrieval time, declared license, hash and transformation
history. It does not cache media. The user provides an email or HTTPS contact for the required
descriptive `User-Agent`; ARPEON does not persist it. The provider may change terms or format,
so re-review is required before broadening the adapter.

Reviewed primary sources:

- [Wikimedia Foundation API usage guidelines](https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_API_Usage_Guidelines)
- [Wikimedia Foundation User-Agent policy](https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_User-Agent_Policy)
- [MediaWiki imageinfo API](https://www.mediawiki.org/wiki/API:Imageinfo)
- [Wikimedia Commons machine-readable metadata](https://commons.wikimedia.org/wiki/Commons:Machine-readable_data)
- [Example Commons CC0 MIDI file](https://commons.wikimedia.org/wiki/File:MIDI_sample_2018-03-21.mid)
- [MuseScore.com terms](https://musescore.com/legal/terms)
- [Cifra Melódica terms](https://ciframelodica.com.br/termos-de-uso/)

Do not infer a site's permission from public accessibility alone. The MuseScore and Cifra
Melódica classifications follow the authoritative ARPEON specification until a current official
integration or explicit authorization permits a specific automated use.
