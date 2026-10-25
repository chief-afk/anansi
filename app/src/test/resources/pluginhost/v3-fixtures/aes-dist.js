// Requires the @libs/aes library, which resolves to the vendored cipher
// namespaces (chacha, salsa, aes) in the host require map.
const aes = require("@libs/aes");

exports.default = {
  id: "fixture-aes",
  name: "Uses Aes",
  site: "https://fixture.invalid/",
  lang: "en",
  version: "1.0.0",
  popularNovels: async function () {
    return aes.unreachable || [];
  },
  parseNovel: async function (novelPath) {
    return { name: "N", path: novelPath, chapters: [] };
  },
  parseChapter: async function () {
    return "";
  },
  searchNovels: async function () {
    return [];
  },
};
