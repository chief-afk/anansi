// Scripted LNReader v3 fixture dist (Anansi's own fixture code, MIT):
// exercises the full v3 adapter surface over recorded novelfull.com
// responses. It fetches through the host's @libs/fetch bridge, parses with
// the bundled cheerio, exposes filter and pluginSettings schemas, and
// declares an imageRequestInit with a Referer header like real plugins do,
// so the default User-Agent injection has a real case to preserve.
// Unlike the real NovelFull dist, it never sleeps, so the host's loud
// timer rejection cannot interfere with the adapter tests.
const fetchApi = require("@libs/fetch").fetchApi;
const cheerio = require("cheerio");

function infoField($, label) {
  let out = "";
  $(".info div").each(function (i, el) {
    if ($(el).find("h3").first().text().trim() === label) {
      out = $(el).text().replace(label, "").trim();
    }
  });
  return out === "" ? undefined : out;
}

function parseList($, plugin) {
  const prefix = String(plugin.pluginSettings.prefix.value || "");
  const limit = parseInt(plugin.filters.limit.value, 10);
  const items = [];
  $(".list-truyen .row").each(function (i, el) {
    const link = $(el).find(".truyen-title a");
    if (!link.length) return;
    const cover = $(el).find("img.cover");
    items.push({
      name: prefix + link.attr("title"),
      path: String(link.attr("href")).replace(/^\//, ""),
      cover:
        cover.length && cover.attr("src")
          ? new URL(cover.attr("src"), plugin.site).href
          : undefined,
    });
  });
  if (isNaN(limit) || limit <= 0) return items;
  return items.slice(0, limit);
}

exports.default = {
  id: "fixture-v3",
  name: "Fixture V3",
  site: "https://novelfull.com/",
  lang: "en",
  version: "1.0.0",
  imageRequestInit: { headers: { Referer: "https://novelfull.com/" } },
  filters: {
    limit: {
      type: "Picker",
      label: "Limit",
      value: "0",
      options: [
        { label: "All", value: "0" },
        { label: "Two", value: "2" },
      ],
    },
  },
  pluginSettings: {
    prefix: {
      type: "Select",
      label: "Name prefix",
      value: "",
      options: [
        { label: "None", value: "" },
        { label: "Marked", value: "[P] " },
      ],
    },
  },
  popularNovels: async function (pageNo) {
    return this.fetchList("most-popular?page=" + pageNo);
  },
  searchNovels: async function (query, pageNo) {
    const params = new URLSearchParams({ keyword: query, page: String(pageNo) });
    return this.fetchList("search?" + params.toString());
  },
  fetchList: async function (urlPath) {
    const plugin = this;
    const response = await fetchApi(plugin.site + urlPath);
    if (!response.ok) {
      throw new Error("Could not reach site ('" + response.status + "')");
    }
    return parseList(cheerio.load(await response.text()), plugin);
  },
  parseNovel: async function (novelPath) {
    const plugin = this;
    const response = await fetchApi(plugin.site + novelPath);
    if (!response.ok) {
      throw new Error("Could not reach site ('" + response.status + "')");
    }
    const $ = cheerio.load(await response.text());
    const cover = $(".books .book img").attr("src");
    return {
      name: $(".books .desc h3.title").first().text(),
      path: novelPath,
      cover: cover ? new URL(cover, plugin.site).href : undefined,
      author: infoField($, "Author:"),
      genres: infoField($, "Genre:"),
      status: infoField($, "Status:"),
      summary: $(".desc-text p")
        .map(function (i, el) {
          return $(el).text();
        })
        .get()
        .join(" "),
      totalPages: 1,
      chapters: $("#list-chapter .list-chapter li a")
        .map(function (i, el) {
          const title = $(el).attr("title");
          const number = /Chapter (\d+(?:\.\d+)?)/.exec(title || "");
          return {
            name: title,
            path: String($(el).attr("href")).replace(/^\//, ""),
            chapterNumber: number ? Number(number[1]) : undefined,
          };
        })
        .get(),
    };
  },
  parseChapter: async function (chapterPath) {
    const response = await fetchApi(this.site + chapterPath);
    if (!response.ok) {
      throw new Error("Could not reach site ('" + response.status + "')");
    }
    const $ = cheerio.load(await response.text());
    return $("#chapter-content").html() || "";
  },
};
