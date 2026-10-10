# 0002 — Browser shell and authenticated loopback bridge

Status: accepted for Milestone 0; session-token lifetime superseded in part by
[ADR 0006](0006-refreshable-local-session.md). Native packaging remains an explicit future decision.

A Java launcher serves the built Angular UI through the JDK HTTP server and opens the
system browser. This delivers the tuner vertical slice without coupling audio to Angular
or adopting a native webview engine prematurely. Gson supplies a tested JSON serializer
(Apache-2.0); avoid hand-escaping JSON and hand-parsing request bodies.

Bind only `127.0.0.1` on an ephemeral port. Require exact Host, same Origin when present,
no cross-site Fetch Metadata, and a cryptographically random 256-bit session token for
all API operations. Do not enable CORS. Deliver the token in a URL fragment; the UI removes
it and keeps it in memory. Do not log the token. Reload requires relaunch. Restrict start
to JSON POST with a 1 KiB body limit and explicit consent. Only measurements cross the bridge.

Start/stop are owned by TunerService. A one-second watchdog ends capture after five
seconds without UI polls, and detects stalled input after two seconds. Navigation away
also requests stop. UI polling is not an audio or musical timing clock. Capture errors,
silence, uncertain input and unstable pitch have separate states.

Use a restrictive CSP, no remote assets, no-store responses, no referrer, no embedded
frames and no browser microphone permission (capture belongs to Java). Local processes
with the same user's privileges are not isolated by this design.

Include a source archive, notices and licenses in distributions, downloadable from the
UI. No automatic updater, remote access, installation service or runtime audio persistence.

Tradeoffs: this is a desktop-launched browser shell, not an installer or dedicated native
window. Java Sound device availability and OS microphone permission need real hardware
validation on each OS. The browser must stay connected while the microphone is active.
