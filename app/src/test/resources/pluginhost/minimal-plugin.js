// Scripted minimal plugin for the plugin-host fixture test: exercises the
// whole require map without touching the network.
const cheerio = require("cheerio");
const dayjs = require("dayjs");
const htmlparser2 = require("htmlparser2");
const storage = require("@libs/storage");
const utils = require("@libs/utils");
const urlencode = require("urlencode");
const novelStatus = require("@libs/novelStatus");
const filterInputs = require("@libs/filterInputs");
const defaultCover = require("@libs/defaultCover");
const isAbsoluteUrl = require("@libs/isAbsoluteUrl");
const constants = require("@/types/constants");

exports.default = {
  id: "fixture-minimal",
  name: "Fixture Minimal",
  site: "https://fixture.invalid/",
  lang: "en",
  version: "1.0.0",
  filters: {
    type: {
      type: filterInputs.FilterTypes.Picker,
      label: "Listing",
      value: "hot",
      options: [{ label: "Hot", value: "hot" }],
    },
  },
  popularNovels: async function (pageNo, options) {
    const $ = cheerio.load(
      '<div class="list"><h3><a href="/n/one.html" title="One">One</a></h3></div>',
    );
    const items = [];
    $("h3 a").each(function (i, el) {
      items.push({
        name: $(el).attr("title"),
        path: $(el).attr("href").substring(1),
      });
    });
    return items;
  },
  parseChapter: async function (chapterPath) {
    const formatted = dayjs("2026-01-15").format("YYYY/MM/DD");
    storage.storage.set("lastStamp", formatted);
    return formatted;
  },
  readStoredStamp: async function () {
    return storage.storage.get("lastStamp");
  },
  probe: async function () {
    let text = "";
    const parser = new htmlparser2.Parser({ ontext: (d) => (text += d) });
    parser.end("<p>hi</p>");
    return {
      filterTypeValue: this.filters.type.value,
      dayjsFormat: dayjs("2026-01-15").format("YYYY/MM/DD"),
      utf8RoundTrip: utils.bytesToUtf8(utils.utf8ToBytes("h\u00e9llo")),
      urlencodeEncode: urlencode.encode("a b"),
      urlencodeDecode: urlencode.decode("a%20b"),
      absoluteUrl: isAbsoluteUrl.isUrlAbsolute("https://x.test/a"),
      relativeUrl: isAbsoluteUrl.isUrlAbsolute("/a"),
      novelStatus: novelStatus.NovelStatus.Ongoing,
      filterType: filterInputs.FilterTypes.Picker,
      coverPrefix: defaultCover.defaultCover.slice(0, 8),
      constantsStatus: constants.NovelStatus.Completed,
      htmlparser2Text: text,
      fetchApiPresent: typeof require("@libs/fetch").fetchApi === "function",
      fetchProtoPresent: typeof require("@libs/fetch").fetchProto === "function",
    };
  },
};
