/*
 * Anansi plugin host glue. Evaluated into a fresh engine BEFORE the vendor
 * bundle so the bundle can never capture the host's fetch bridge.
 *
 * Sections: platform surface the engine lacks (base64, UTF-8
 * TextEncoder/TextDecoder, URL/URLSearchParams, console, timers), the
 * id-multiplexed fetch bridge with a Response-like object, the per-plugin
 * storage scopes, the 12-entry require map, and the dist loader/caller.
 *
 * Loud-failure convention: errors the host itself rejects are thrown as
 * `anansi/unsupported-feature: <reason>`; the Kotlin boundary maps that
 * prefix to the authoritative SourceError variant. Everything else keeps
 * its raw message.
 */
(function (global) {
  "use strict";

  if (typeof global.__anansiHostInstalled !== "undefined") {
    return;
  }

  function loudUnsupported(reason) {
    return new Error("anansi/unsupported-feature: " + reason);
  }

  // --- base64 (the engine has no atob/btoa) ---

  var B64_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

  function bytesToBase64(bytes) {
    var out = "";
    for (var i = 0; i < bytes.length; i += 3) {
      var b0 = bytes[i];
      var b1 = i + 1 < bytes.length ? bytes[i + 1] : 0;
      var b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
      out += B64_ALPHABET.charAt(b0 >> 2);
      out += B64_ALPHABET.charAt(((b0 & 3) << 4) | (b1 >> 4));
      out += i + 1 < bytes.length ? B64_ALPHABET.charAt(((b1 & 15) << 2) | (b2 >> 6)) : "=";
      out += i + 2 < bytes.length ? B64_ALPHABET.charAt(b2 & 63) : "=";
    }
    return out;
  }

  function base64ToBytes(text) {
    var clean = String(text).replace(/[^A-Za-z0-9+/]/g, "");
    var out = [];
    for (var i = 0; i < clean.length; i += 4) {
      var c0 = B64_ALPHABET.indexOf(clean.charAt(i));
      var c1 = i + 1 < clean.length ? B64_ALPHABET.indexOf(clean.charAt(i + 1)) : 0;
      var c2 = i + 2 < clean.length ? B64_ALPHABET.indexOf(clean.charAt(i + 2)) : -1;
      var c3 = i + 3 < clean.length ? B64_ALPHABET.indexOf(clean.charAt(i + 3)) : -1;
      out.push((c0 << 2) | (c1 >> 4));
      if (c2 >= 0) out.push(((c1 & 15) << 4) | (c2 >> 2));
      if (c3 >= 0) out.push(((c2 & 3) << 6) | c3);
    }
    return new Uint8Array(out);
  }

  // --- UTF-8-only TextEncoder/TextDecoder (platform surface) ---

  if (typeof global.TextEncoder === "undefined") {
    global.TextEncoder = function TextEncoder() {};
    global.TextEncoder.prototype.encode = function (text) {
      var bytes = [];
      var str = String(text);
      for (var i = 0; i < str.length; ) {
        var code = str.codePointAt(i);
        if (code < 0x80) {
          bytes.push(code);
        } else if (code < 0x800) {
          bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
        } else if (code < 0x10000) {
          bytes.push(
            0xe0 | (code >> 12),
            0x80 | ((code >> 6) & 0x3f),
            0x80 | (code & 0x3f),
          );
        } else {
          bytes.push(
            0xf0 | (code >> 18),
            0x80 | ((code >> 12) & 0x3f),
            0x80 | ((code >> 6) & 0x3f),
            0x80 | (code & 0x3f),
          );
        }
        i += code > 0xffff ? 2 : 1;
      }
      return new Uint8Array(bytes);
    };
  }

  if (typeof global.TextDecoder === "undefined") {
    global.TextDecoder = function TextDecoder() {};
    global.TextDecoder.prototype.decode = function (bytes) {
      var data = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
      var start = 0;
      // Skip a UTF-8 BOM.
      if (data.length >= 3 && data[0] === 0xef && data[1] === 0xbb && data[2] === 0xbf) {
        start = 3;
      }
      var out = "";
      var i = start;
      while (i < data.length) {
        var b = data[i];
        var code;
        var extra;
        if (b < 0x80) {
          code = b;
          extra = 0;
        } else if ((b & 0xe0) === 0xc0 && data.length - i >= 2) {
          code = b & 0x1f;
          extra = 1;
        } else if ((b & 0xf0) === 0xe0 && data.length - i >= 3) {
          code = b & 0x0f;
          extra = 2;
        } else if ((b & 0xf8) === 0xf0 && data.length - i >= 4) {
          code = b & 0x07;
          extra = 3;
        } else {
          out += "\uFFFD";
          i += 1;
          continue;
        }
        var valid = true;
        for (var k = 1; k <= extra; k++) {
          if ((data[i + k] & 0xc0) !== 0x80) {
            valid = false;
            break;
          }
          code = (code << 6) | (data[i + k] & 0x3f);
        }
        if (!valid) {
          out += "\uFFFD";
          i += 1;
          continue;
        }
        out += String.fromCodePoint(code);
        i += extra + 1;
      }
      return out;
    };
  }

  function utf8Decode(bytes) {
    return new global.TextDecoder().decode(bytes);
  }

  // --- URLSearchParams and a read-surface URL (platform surface) ---

  if (typeof global.URLSearchParams === "undefined") {
    var USP = function (init) {
      this._pairs = [];
      if (typeof init === "string") {
        var self = this;
        String(init)
          .replace(/^\?/, "")
          .split("&")
          .forEach(function (pair) {
            if (pair === "") return;
            var eq = pair.indexOf("=");
            var name = eq < 0 ? pair : pair.slice(0, eq);
            var value = eq < 0 ? "" : pair.slice(eq + 1);
            self._pairs.push([
              decodeURIComponent(name.replace(/\+/g, " ")),
              decodeURIComponent(value.replace(/\+/g, " ")),
            ]);
          });
      } else if (Array.isArray(init)) {
        for (var i = 0; i < init.length; i++) {
          this._pairs.push([String(init[i][0]), String(init[i][1])]);
        }
      } else if (init != null && typeof init === "object") {
        for (var key in init) {
          if (Object.prototype.hasOwnProperty.call(init, key)) {
            this._pairs.push([key, String(init[key])]);
          }
        }
      }
    };
    USP.prototype.append = function (name, value) {
      this._pairs.push([String(name), String(value)]);
    };
    USP.prototype.set = function (name, value) {
      name = String(name);
      value = String(value);
      var replaced = false;
      var next = [];
      for (var i = 0; i < this._pairs.length; i++) {
        if (this._pairs[i][0] === name && !replaced) {
          next.push([name, value]);
          replaced = true;
        } else if (this._pairs[i][0] !== name) {
          next.push(this._pairs[i]);
        }
      }
      if (!replaced) next.push([name, value]);
      this._pairs = next;
    };
    USP.prototype.delete = function (name) {
      var removed = [];
      for (var i = 0; i < this._pairs.length; i++) {
        if (this._pairs[i][0] !== String(name)) removed.push(this._pairs[i]);
      }
      this._pairs = removed;
    };
    USP.prototype.get = function (name) {
      for (var i = 0; i < this._pairs.length; i++) {
        if (this._pairs[i][0] === String(name)) return this._pairs[i][1];
      }
      return null;
    };
    USP.prototype.getAll = function (name) {
      var found = [];
      for (var i = 0; i < this._pairs.length; i++) {
        if (this._pairs[i][0] === String(name)) found.push(this._pairs[i][1]);
      }
      return found;
    };
    USP.prototype.has = function (name) {
      for (var i = 0; i < this._pairs.length; i++) {
        if (this._pairs[i][0] === String(name)) return true;
      }
      return false;
    };
    USP.prototype.forEach = function (callback, thisArg) {
      for (var i = 0; i < this._pairs.length; i++) {
        callback.call(thisArg, this._pairs[i][1], this._pairs[i][0], this);
      }
    };
    USP.prototype.keys = function () {
      return this._pairs.map(function (p) {
        return p[0];
      });
    };
    USP.prototype.values = function () {
      return this._pairs.map(function (p) {
        return p[1];
      });
    };
    USP.prototype.entries = function () {
      return this._pairs.slice();
    };
    USP.prototype[Symbol.iterator] = function () {
      return this._pairs.slice()[Symbol.iterator]();
    };
    USP.prototype.toString = function () {
      return this._pairs
        .map(function (p) {
          return encodeURIComponent(p[0]) + "=" + encodeURIComponent(p[1]);
        })
        .join("&");
    };
    global.URLSearchParams = USP;
  }

  if (typeof global.URL === "undefined") {
    var SCHEME_RE = /^[a-zA-Z][a-zA-Z0-9+.-]*:/;

    var AnansiURL = function (url, base) {
      var resolved = AnansiURL._resolve(String(url), base == null ? null : String(base));
      var match = /^([a-zA-Z][a-zA-Z0-9+.-]*:)(\/\/[^/?#]*)?([^?#]*)(\?[^#]*)?(#.*)?$/.exec(resolved);
      if (!match) {
        throw new TypeError("Invalid URL: " + url);
      }
      this.protocol = match[1].toLowerCase();
      this._authority = match[2] || "";
      var hostport = this._authority.replace(/^\/\//, "");
      var atSign = hostport.lastIndexOf("@");
      if (atSign >= 0) hostport = hostport.slice(atSign + 1);
      var colon = hostport.lastIndexOf(":");
      var hasPort = colon >= 0 && /^\d+$/.test(hostport.slice(colon + 1));
      this.hostname = hasPort ? hostport.slice(0, colon) : hostport;
      this.port = hasPort ? hostport.slice(colon + 1) : "";
      this.host = this.port ? this.hostname + ":" + this.port : this.hostname;
      this.origin = this.protocol + "//" + this.host;
      var path = match[3] || "";
      if (path === "" && this._authority !== "") path = "/";
      this.pathname = path;
      this.search = match[4] || "";
      this.hash = match[5] || "";
      this.searchParams = new global.URLSearchParams(this.search);
    };

    AnansiURL.prototype.toString = function () {
      return (
        this.protocol + this._authority + this.pathname + this.search + this.hash
      );
    };

    Object.defineProperty(AnansiURL.prototype, "href", {
      get: function () {
        return this.toString();
      },
    });

    AnansiURL.canParseAbsolute = function (url) {
      return /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(url);
    };

    // Reference resolution against an absolute base (RFC 3986 read surface).
    AnansiURL._resolve = function (url, base) {
      if (AnansiURL.canParseAbsolute(url)) return url;
      if (url.indexOf("//") === 0) {
        if (!base || !AnansiURL.canParseAbsolute(base)) {
          throw new TypeError("Invalid base URL for " + url);
        }
        return base.slice(0, base.indexOf("://") + 1) + url;
      }
      var b = base && /^([a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/.exec(base);
      if (!b) throw new TypeError("Invalid base URL: " + base);
      var root = b[1];
      var basePath = b[2] || "/";
      if (url === "") return root + basePath + (b[3] || "") + (b[4] || "");
      if (url.charAt(0) === "/") return root + url;
      if (url.charAt(0) === "?") return root + basePath + url;
      if (url.charAt(0) === "#") return root + basePath + (b[3] || "") + url;
      var segments = (basePath.slice(0, basePath.lastIndexOf("/") + 1) + url).split("/");
      var out = [];
      for (var i = 0; i < segments.length; i++) {
        var seg = segments[i];
        if (seg === ".") continue;
        if (seg === "..") {
          if (out.length > 0 && out[out.length - 1] !== "") out.pop();
          continue;
        }
        out.push(seg);
      }
      return root + out.join("/");
    };

    global.URL = AnansiURL;
  }

  // --- console -> host log; timers are loud (the host provides none) ---

  if (typeof global.console === "undefined") {
    global.console = {
      log: function () { emitLog("log", arguments); },
      info: function () { emitLog("info", arguments); },
      warn: function () { emitLog("warn", arguments); },
      error: function () { emitLog("error", arguments); },
      debug: function () { emitLog("debug", arguments); },
    };
  }

  function emitLog(level, args) {
    var parts = [];
    for (var i = 0; i < args.length; i++) {
      var value = args[i];
      try {
        parts.push(typeof value === "string" ? value : JSON.stringify(value));
      } catch (e) {
        parts.push(String(value));
      }
    }
    try {
      global.__anansiLog(level, parts.join(" "));
    } catch (e) {
      /* logging must never break plugin execution */
    }
  }

  global.setTimeout = function () {
    throw loudUnsupported("this host provides no timers (setTimeout)");
  };
  global.setInterval = function () {
    throw loudUnsupported("this host provides no timers (setInterval)");
  };
  global.clearTimeout = function () {
    throw loudUnsupported("this host provides no timers (clearTimeout)");
  };
  global.clearInterval = function () {
    throw loudUnsupported("this host provides no timers (clearInterval)");
  };

  // --- per-plugin storage scopes (@libs/storage, shim.ts semantics) ---

  var storageScopes = Object.create(null);

  function readStorageDoc(pluginId) {
    return JSON.parse(global.__anansiStorageGet(pluginId));
  }

  function writeStorageDoc(pluginId, doc) {
    global.__anansiStorageSet(pluginId, JSON.stringify(doc));
  }

  function emptyStorageDoc() {
    return { storage: {}, local: null, session: null };
  }

  function storageScopeFor(pluginId) {
    var cached = storageScopes[pluginId];
    if (cached) return cached;

    function makeStore(kind) {
      return {
        set: function (key, value, expires) {
          var doc = readStorageDoc(pluginId);
          if (!doc.storage) doc.storage = {};
          doc.storage[key] = {
            created: new Date().toISOString(),
            value: value,
            expires: expires instanceof Date ? expires.getTime() : expires,
          };
          writeStorageDoc(pluginId, doc);
        },
        get: function (key, raw) {
          var doc = readStorageDoc(pluginId);
          var item = doc.storage ? doc.storage[key] : undefined;
          if (item === undefined || item === null) return undefined;
          if (item.expires && Date.now() > item.expires) {
            delete doc.storage[key];
            writeStorageDoc(pluginId, doc);
            return undefined;
          }
          return raw ? item : item.value;
        },
        delete: function (key) {
          var doc = readStorageDoc(pluginId);
          if (doc.storage && key in doc.storage) {
            delete doc.storage[key];
            writeStorageDoc(pluginId, doc);
          }
        },
        clearAll: function () {
          var doc = readStorageDoc(pluginId);
          doc.storage = {};
          writeStorageDoc(pluginId, doc);
        },
        getAllKeys: function () {
          var doc = readStorageDoc(pluginId);
          return doc.storage ? Object.keys(doc.storage) : [];
        },
      };
    }

    function makeWebStore(kind) {
      return {
        get: function () {
          var doc = readStorageDoc(pluginId);
          return doc[kind] ? doc[kind].value : undefined;
        },
      };
    }

    var scope = {
      storage: makeStore("storage"),
      localStorage: makeWebStore("local"),
      sessionStorage: makeWebStore("session"),
    };
    storageScopes[pluginId] = scope;
    return scope;
  }

  // --- fetch bridge (id-multiplexed envelope) ---

  var fetchSeq = 0;

  function normalizeHeaders(headers) {
    var flat = {};
    if (headers == null) return flat;
    if (typeof headers.forEach === "function") {
      headers.forEach(function (value, name) {
        flat[name.toLowerCase() === "user-agent" ? "User-Agent" : name] = String(value);
      });
      return flat;
    }
    for (var name in headers) {
      if (Object.prototype.hasOwnProperty.call(headers, name)) {
        flat[name] = String(headers[name]);
      }
    }
    return flat;
  }

  function bodyToBase64(body) {
    if (body == null) return null;
    if (typeof body === "string") return bytesToBase64(new global.TextEncoder().encode(body));
    if (body instanceof Uint8Array) return bytesToBase64(body);
    throw loudUnsupported(
      "fetch body type " + (body && body.constructor && body.constructor.name) +
        " is not supported (use string or Uint8Array)",
    );
  }

  function makeResponse(envelope) {
    var ok = envelope.ok;
    var headerMap = ok.headers || {};
    var responseText = null;

    function decodeBody() {
      if (responseText !== null) return responseText;
      if (typeof ok.body === "string") {
        responseText = ok.body;
      } else if (typeof ok.bodyBase64 === "string") {
        responseText = utf8Decode(base64ToBytes(ok.bodyBase64));
      } else {
        responseText = "";
      }
      return responseText;
    }

    function unsupportedReader() {
      throw loudUnsupported(
        "this host provides no blob/arrayBuffer responses (protobuf readers are unsupported)",
      );
    }

    return {
      url: ok.url || "",
      status: ok.status,
      statusText: ok.statusText || "",
      ok: ok.status >= 200 && ok.status < 300,
      headers: {
        get: function (name) {
          var value = headerMap[String(name).toLowerCase()];
          return value === undefined ? null : value;
        },
        has: function (name) {
          return String(name).toLowerCase() in headerMap;
        },
      },
      text: function () {
        return Promise.resolve(decodeBody());
      },
      json: function () {
        return Promise.resolve(JSON.parse(decodeBody()));
      },
      blob: unsupportedReader,
      arrayBuffer: unsupportedReader,
    };
  }

  global.fetch = function (input, init) {
    var url;
    var options = init || {};
    if (typeof input === "string") {
      url = input;
    } else if (input && typeof input.url === "string") {
      url = input.url;
    } else {
      throw new TypeError("fetch requires a URL string");
    }
    var request = {
      id: ++fetchSeq,
      url: url,
      method: String(options.method || "GET").toUpperCase(),
      headers: normalizeHeaders(options.headers),
    };
    var bodyBase64 = bodyToBase64(options.body);
    if (bodyBase64 !== null) request.bodyBase64 = bodyBase64;

    return Promise.resolve(global.__anansiFetch(JSON.stringify(request))).then(function (envJson) {
      var envelope = JSON.parse(envJson);
      if (envelope.id !== request.id) {
        throw new Error("fetch envelope id mismatch: " + envelope.id + " != " + request.id);
      }
      if (envelope.err) {
        throw new Error(envelope.err);
      }
      return makeResponse(envelope);
    });
  };

  // --- require map (12 entries; @libs/aes fails loudly, never a stub) ---

  var DEFAULT_COVER =
    "https://github.com/LNReader/lnreader-plugins/blob/main/icons/src/coverNotAvailable.jpg?raw=true";

  var NovelStatus = {
    Unknown: "Unknown",
    Ongoing: "Ongoing",
    Completed: "Completed",
    Licensed: "Licensed",
    PublishingFinished: "Publishing Finished",
    Cancelled: "Cancelled",
    OnHiatus: "On Hiatus",
  };

  var FilterTypes = {
    TextInput: "Text",
    Picker: "Picker",
    CheckboxGroup: "Checkbox",
    Switch: "Switch",
    ExcludableCheckboxGroup: "XCheckbox",
  };

  function isUrlAbsolute(url) {
    if (!url) return false;
    if (url.indexOf("//") === 0) return true;
    if (url.indexOf("://") === -1) return false;
    if (url.indexOf(".") === -1) return false;
    if (url.indexOf("/") === -1) return false;
    if (url.indexOf(":") > url.indexOf("/")) return false;
    if (url.indexOf("://") < url.indexOf(".")) return true;
    return false;
  }

  function urlencodeFor(pluginId) {
    return {
      encode: function (text) {
        var bytes = new global.TextEncoder().encode(String(text));
        var out = [];
        for (var i = 0; i < bytes.length; i++) {
          var b = bytes[i];
          if (
            (b >= 0x61 && b <= 0x7a) ||
            (b >= 0x41 && b <= 0x5a) ||
            (b >= 0x30 && b <= 0x39) ||
            b === 0x2d || b === 0x5f || b === 0x2e || b === 0x7e
          ) {
            out.push(String.fromCharCode(b));
          } else {
            var hex = b.toString(16).toUpperCase();
            out.push("%" + (hex.length < 2 ? "0" + hex : hex));
          }
        }
        return out.join("");
      },
      decode: function (text) {
        var s = String(text);
        var bytes = [];
        for (var i = 0; i < s.length; i++) {
          var c = s.charAt(i);
          if (c === "%") {
            bytes.push(parseInt(s.substr(i + 1, 2), 16));
            i += 2;
          } else {
            var code = s.charCodeAt(i);
            if (code < 0x80) {
              bytes.push(code);
            } else {
              var encoded = encodeURIComponent(c);
              for (var k = 0; k < encoded.length; k += 3) {
                bytes.push(parseInt(encoded.substr(k + 1, 2), 16));
              }
            }
          }
        }
        return utf8Decode(new Uint8Array(bytes));
      },
    };
  }

  function requireMapFor(pluginId) {
    var vendor = global.__anansiVendor;
    if (!vendor) {
      throw new Error("Vendor bundle is not loaded into the plugin host");
    }
    var map = {
      "@libs/storage": storageScopeFor(pluginId),
      htmlparser2: { Parser: vendor.htmlparser2.Parser },
      cheerio: { load: vendor.cheerio.load },
      dayjs: vendor.dayjs,
      urlencode: urlencodeFor(pluginId),
      "@libs/novelStatus": { NovelStatus: NovelStatus },
      "@libs/fetch": {
        fetchApi: global.fetch,
        fetchText: function (url, init, encoding) {
          return global.fetch(url, init).then(function (response) {
            return response.text();
          });
        },
        fetchProto: function () {
          throw loudUnsupported(
            "fetchProto is unsupported until protobuf ships with a plugin that needs it",
          );
        },
      },
      "@libs/isAbsoluteUrl": { isUrlAbsolute: isUrlAbsolute },
      "@libs/filterInputs": { FilterTypes: FilterTypes },
      "@libs/defaultCover": { defaultCover: DEFAULT_COVER },
      "@libs/utils": {
        utf8ToBytes: function (text) {
          return new global.TextEncoder().encode(String(text));
        },
        bytesToUtf8: function (bytes) {
          return utf8Decode(bytes);
        },
      },
      "@/types/constants": { NovelStatus: NovelStatus, defaultCover: DEFAULT_COVER },
    };
    return function (packageName) {
      if (packageName === "@libs/aes") {
        throw loudUnsupported(
          "Plugin \"" + pluginId + "\" uses the unsupported @libs/aes library and cannot run",
        );
      }
      var lib = map[packageName];
      if (!lib) {
        throw loudUnsupported("Unsupported plugin library: " + packageName);
      }
      return lib;
    };
  }

  // --- dist loader, inspector, caller ---

  var plugins = Object.create(null);

  var CONTRACT_MEMBERS = [
    "popularNovels",
    "parseNovel",
    "parseNovelAndChapters",
    "parseChapter",
    "parsePage",
    "searchNovels",
    "resolveUrl",
  ];

  global.__anansiLoadPlugin = function (pluginId, code) {
    if (!code || !String(code).trim()) {
      throw new Error("Plugin \"" + pluginId + "\" dist is empty");
    }
    var requireShim = requireMapFor(pluginId);
    var module = { exports: {} };
    var factory = new Function(
      "require",
      "module",
      "const exports = module.exports = {};\n" + code + "\nreturn exports.default",
    );
    var plugin = factory(requireShim, module);
    if (!plugin || typeof plugin.popularNovels !== "function") {
      throw new Error("Plugin \"" + pluginId + "\" did not expose a valid plugin");
    }
    plugins[pluginId] = plugin;
  };

  global.__anansiInspectPlugin = function (pluginId) {
    var plugin = plugins[pluginId];
    if (!plugin) {
      throw new Error("Plugin \"" + pluginId + "\" is not loaded");
    }
    var members = {};
    CONTRACT_MEMBERS.forEach(function (name) {
      members[name] = typeof plugin[name] === "function";
    });
    return JSON.stringify({
      pluginId: pluginId,
      id: plugin.id,
      name: plugin.name,
      site: plugin.site,
      lang: plugin.lang,
      version: plugin.version,
      iconUrl: plugin.iconUrl == null ? null : plugin.iconUrl,
      members: members,
      hasFilters: plugin.filters != null,
      hasPluginSettings: plugin.pluginSettings != null,
      filters: plugin.filters == null ? null : plugin.filters,
      pluginSettings: plugin.pluginSettings == null ? null : plugin.pluginSettings,
    });
  };

  global.__anansiCallPlugin = async function (pluginId, method, argsJson) {
    var plugin = plugins[pluginId];
    if (!plugin) {
      throw new Error("Plugin \"" + pluginId + "\" is not loaded");
    }
    var fn = plugin[method];
    if (typeof fn !== "function") {
      throw loudUnsupported("Plugin \"" + pluginId + "\" has no \"" + method + "\" member");
    }
    var args = JSON.parse(argsJson);
    if (method === "popularNovels" && args.length >= 1) {
      if (args[1] == null) args[1] = {};
      if (args[1].filters === undefined && plugin.filters !== undefined) {
        args[1].filters = plugin.filters;
      }
    }
    if (method === "searchNovels" && plugin.filters !== undefined) {
      while (args.length < 3) args.push(undefined);
      if (args[2] === undefined) args[2] = plugin.filters;
    }
    var result = await fn.apply(plugin, args);
    return JSON.stringify(result === undefined ? null : result);
  };

  // Call completion travels through __anansiCallResult because engine
  // evaluations capture plain values, not settled promises. The call id
  // rides in the envelope and in the result callback, so each envelope is
  // routed to its own caller and concurrent calls cannot receive each
  // other's results. The envelope keeps the two failure classes
  // distinguishable at the Kotlin boundary.
  global.__anansiCallPluginAsync = function (callId, pluginId, method, argsJson) {
    return global
      .__anansiCallPlugin(pluginId, method, argsJson)
      .then(function (resultJson) {
        return JSON.stringify({ id: callId, ok: resultJson });
      })
      .catch(function (e) {
        var message = e && e.message ? e.message : String(e);
        return JSON.stringify({ id: callId, failed: message });
      });
  };

  global.__anansiHostInstalled = true;
})(typeof globalThis !== "undefined" ? globalThis : this);
