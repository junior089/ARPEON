# ADR 0006 — Refreshable local browser session

Status: accepted.

## Context

The initial desktop bridge placed a 256-bit random token in the URL fragment,
then kept it only in Angular memory. This prevented refresh from restoring the UI,
but the running Java server and score workspace remained alive.

## Decision

The UI consumes the launch fragment and stores the token in `sessionStorage` for
the lifetime of that browser tab. The fragment is removed from the visible URL.
The token remains scoped to the exact origin (including the ephemeral port) and
is not written to `localStorage`, cookies, project files or logs. Closing the tab
ends that browser session; launching the application again issues a new token.

On UI startup, the frontend calls the authenticated `/api/session` endpoint. If a
score is open, it retrieves `/api/score` and restores the active project ID and
revision state. Project contents remain in the running Java process or in the
local project store; transport and microphone sessions are not resumed by the UI.
The existing five-second capture and playback poll leases still stop unattended
operations.

The bridge continues to bind only to `127.0.0.1` on an ephemeral port, validate
Host and Origin, reject cross-site Fetch Metadata, disable CORS, and require the
random token on every API call. The frontend has no remote script assets and
uses the existing restrictive Content Security Policy.

## Consequences

- Refreshing the same tab reconnects to the running application without putting
  the token back in the URL.
- `sessionStorage` is readable by same-origin JavaScript. The UI bundle and its
  dependencies therefore remain part of the trusted computing base; adding
  remote scripts or weakening the CSP requires a security review.
- A browser tab cannot reconnect after the Java process exits. The user must open
  the new launch URL, which carries that process's new token.
- Existing project listing lets the user reopen saved work after a process restart.
