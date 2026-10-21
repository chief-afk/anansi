// Scripted LNReader v1-era fixture module (Anansi's own fixture code, MIT):
// modeled on the verified v1-era contract shapes (freewebnovel.ts). It
// calls the bare global fetch (the v1-era convention, not @libs/fetch),
// parses the recorded libread.com responses with the bundled cheerio,
// returns the { novels } envelope from popularNovels, takes two arguments
// in parseChapter and returns a SourceChapter object, answers searchNovels
// with a bare array from a single search-term argument, and exposes a
// multisrc-style filters array with the numeric inputType values compiled
// v1-era dists inline.
const cheerio = require("cheerio");

const sourceId = 13;
const sourceName = "Fixture V1 Era";
const baseUrl = "https://libread.com";

const filters = [
  {
    key: "sort",
    label: "Sort By",
    values: [
      { label: "Completed", value: "completed" },
      { label: "Latest", value: "latest" },
    ],
    inputType: 1,
  },
];

function absolute(href) {
  return href == null ? undefined : baseUrl + href;
}

function parseList($) {
  return $(".li-row")
    .map(function (i, el) {
      return {
        sourceId: sourceId,
        novelName: $(el).find(".tit").text(),
        novelCover: absolute($(el).find("img").attr("src")),
        novelUrl: absolute($(el).find(".tit a").attr("href")),
      };
    })
    .get();
}

const popularNovels = async function (page, options) {
  const showLatestNovels = !!(options && options.showLatestNovels);
  const filterValues = options && options.filters;
  const sort =
    showLatestNovels || (filterValues && filterValues.sort === "latest")
      ? "/sort/latest-release-novels/"
      : "/completed-novels/";
  const response = await fetch(baseUrl + sort + page);
  if (!response.ok) {
    throw new Error("Could not reach site ('" + response.status + "')");
  }
  return { novels: parseList(cheerio.load(await response.text())) };
};

const parseNovelAndChapters = async function (novelUrl) {
  const response = await fetch(novelUrl);
  if (!response.ok) {
    throw new Error("Could not reach site ('" + response.status + "')");
  }
  const $ = cheerio.load(await response.text());
  const stripTabs = function (text) {
    return String(text).replace(/[\t\n]/g, "");
  };
  const novel = {
    sourceId: sourceId,
    sourceName: sourceName,
    novelUrl: novelUrl,
    url: novelUrl,
    novelName: $("h1.tit").first().text(),
    novelCover: absolute($(".pic").first().find("img").attr("src")),
    summary: $(".inner").first().text().trim(),
    genre: stripTabs($("[title=Genre]").first().next().text()),
    author: stripTabs($("[title=Author]").first().next().text()),
    status:
      stripTabs($("[title=Status]").first().next().text()) === "OnGoing"
        ? "Ongoing"
        : "Completed",
    chapters: $("#idData > li > a")
      .map(function (i, el) {
        return {
          chapterName: $(el).attr("title") || "Chapter " + i,
          releaseDate: null,
          chapterUrl: absolute($(el).attr("href")),
        };
      })
      .get(),
  };
  return novel;
};

const parseChapter = async function (novelUrl, chapterUrl) {
  const response = await fetch(chapterUrl);
  if (!response.ok) {
    throw new Error("Could not reach site ('" + response.status + "')");
  }
  const $ = cheerio.load(await response.text());
  return {
    sourceId: sourceId,
    novelUrl: novelUrl,
    chapterUrl: chapterUrl,
    chapterName: $(".chapter").first().text() || $("h1.tit").first().text(),
    chapterText: $("#article").html() || "",
  };
};

const searchNovels = async function (searchTerm) {
  const response = await fetch(baseUrl + "/search/", {
    method: "POST",
    body: "searchkey=" + encodeURIComponent(searchTerm),
    headers: {
      "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
    },
  });
  if (!response.ok) {
    throw new Error("Could not reach site ('" + response.status + "')");
  }
  return parseList(cheerio.load(await response.text()));
};

exports.default = {
  popularNovels: popularNovels,
  parseNovelAndChapters: parseNovelAndChapters,
  parseChapter: parseChapter,
  searchNovels: searchNovels,
  filters: filters,
};
