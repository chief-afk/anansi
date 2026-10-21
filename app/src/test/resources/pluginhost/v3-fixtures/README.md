# v3 adapter fixture set

Fixtures for the LNReader v3 adapter tests. Layout:

- `good-dist.js`: scripted v3 fixture dist (Anansi's own MIT fixture code)
  exercising the full adapter surface. It fetches the recorded novelfull.com
  responses through the host's `@libs/fetch` bridge, parses them with the
  bundled cheerio, exposes filter and pluginSettings schemas whose values
  change catalog output (limit truncation, name prefix), declares an
  imageRequestInit with a Referer header, and returns chapter HTML so the
  adapter's plain-text normalization has a real case. Unlike the real
  NovelFull dist it never sleeps, so the host's loud timer rejection stays
  out of the way.
- `wrong-generation-dist.js`: v1-era shaped code (parseNovelAndChapters, no
  parseNovel) for the generation check.
- `no-contract-dist.js`: no contract members; rejected at the host load gate.
- `aes-dist.js`: requires `@libs/aes`; rejected loudly as unsupported.
- `manifest-broken.json`: crafted manifest with three malformed entries
  (missing field, non-string field, non-object item) and one valid entry.

The HTTP responses live in `../recorded/` and are verbatim captures from
`https://novelfull.com/` taken 2026-10-21 with the host's browser-equivalent
default headers, replayed in tests through an OkHttp Interceptor. They are
test data only: Anansi ships zero sources.
