# Fixture provenance

`index.js` is the compiled LNReader v3 plugin dist for the NovelFull source,
taken verbatim from the upstream publish branch:

- Repository: LNReader/lnreader-plugins (MIT: https://github.com/LNReader/lnreader-plugins/blob/master/LICENSE)
- Branch: `plugins/v3.0.0`, commit `bdfbe15b72c0cd6a0f76540ac15765ba7edc785b`
- Path on that branch: `.js/src/plugins/english/NovelFull[readnovelfull].js`
- Manifest entry: id `novelfull`, version `2.2.1`, site `https://novelfull.com/`

Committed as an end-to-end test fixture only (MIT, license-clean). It is
not a bundled source: Anansi ships zero sources.

The recorded HTTP fixtures in `../recorded/` are verbatim responses captured
from `https://novelfull.com/` on 2026-10-21 with the host's browser-equivalent
default headers, replayed in tests through an OkHttp Interceptor.
