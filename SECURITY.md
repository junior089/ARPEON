# Security

Treat imported files and the browser boundary as untrusted. The local API binds IPv4
loopback on an ephemeral port, validates Host and Origin, rejects cross-site requests,
requires a random per-process session token and does not enable CORS. Microphone start
requires an explicit consent field. Audio never crosses the HTTP bridge.

The UI token is initially delivered in a URL fragment and removed from the address bar.
It remains only in UI memory. Other local processes running as the same user are outside
this boundary's threat model. Never expose the bridge through a proxy or bind it externally.

MusicXML accepts at most 1 MiB of uncompressed XML, forbids DOCTYPE/entities/network
resolution and caps XML depth and attributes. Unsupported musical semantics fail explicitly.
Archives, URLs and external processes are not accepted in Milestone 0.

No public repository URL or private security contact has been configured yet. Before
publishing the repository, maintainers must enable a private reporting channel and add it
here. Do not post credentials, private music or exploitable details in public issues.
For local investigation retain only a minimal, original, non-sensitive reproduction.
