# v1-era adapter fixture set

Fixtures for the LNReader v1-era adapter tests. Layout:

- `good-dist.js`: scripted v1-era fixture module (Anansi's own MIT fixture
  code) modeled on the verified v1-era contract shapes: the `{ novels }`
  envelope from popularNovels, parseNovelAndChapters returning the chapter
  list, the two-argument parseChapter returning a SourceChapter object,
  one-argument searchNovels with a bare-array result, a multisrc-style
  filters array with numeric inputType values, and the bare global `fetch`
  call instead of `@libs/fetch`. It parses the recorded libread.com
  responses with the bundled cheerio and never sleeps, so the host's loud
  timer rejection stays out of the way.
- `no-contract-dist.js`: passes the host's popularNovels load gate but has
  neither parse member; rejected loudly by the adapter's generation check.
- The wrong-generation case reuses `../v3-fixtures/good-dist.js`
  (parseNovel only, the v3 shape), which the v1-era loader must reject.

The HTTP responses live in `../v1era-recorded/` and are verbatim captures
taken 2026-10-21 with the host's browser-equivalent default headers,
replayed in tests through an OkHttp Interceptor:

- `popular-completed-1.html`: `https://libread.com/completed-novels/1`
- `popular-latest-1.html`: `https://libread.com/sort/latest-release-novels/1`
- `novel-martial-world.html`: `https://libread.com/libread/martial-world-novel-137346`
- `chapter-martial-world-1.html`: the response for
  `https://libread.com/libread/martial-world-novel-137346/chapter-01`;
  libread redirects its reader pages to freewebnovel.com, and this is the
  final redirect target's body (`/novel/martial-world-novel/chapter-1`)
- `search-martial.html`: response body of
  `POST https://libread.com/search/` with `searchkey=martial`

They are test data only: Anansi ships zero sources.
