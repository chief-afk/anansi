// Incompatible fixture: v1-era shaped code. It passes the host's
// popularNovels gate but exposes parseNovelAndChapters instead of
// parseNovel, so the v3 adapter's generation check must reject it loudly.
exports.default = {
  id: "fixture-wrong-generation",
  name: "Wrong Generation",
  site: "https://fixture.invalid/",
  lang: "en",
  version: "1.0.0",
  popularNovels: async function () {
    return [];
  },
  parseNovelAndChapters: async function () {
    return {};
  },
  parseChapter: async function () {
    return "";
  },
  searchNovels: async function () {
    return [];
  },
};
