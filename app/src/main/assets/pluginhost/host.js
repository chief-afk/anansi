/*
 * Anansi plugin host glue. Evaluated into a fresh engine BEFORE the vendor
 * bundle so the bundle can never capture the host's fetch bridge.
 *
 * Sections: platform surface the engine lacks (base64, UTF-8
 * TextEncoder/TextDecoder, URL/URLSearchParams, Headers, Blob/File/FormData,
 * AbortController, console, timers), the loud Intl stub, the
 * id-multiplexed fetch bridge with a Response-like object, the per-plugin
 * storage scopes, the 13-entry require map, and the dist loader/caller.
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

  // --- base64 (the engine has no atob/btoa; these helpers back them) ---

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

  if (typeof global.atob === "undefined") {
    global.atob = function (text) {
      var bytes = base64ToBytes(text);
      var out = "";
      var CHUNK = 0x8000;
      for (var i = 0; i < bytes.length; i += CHUNK) {
        out += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
      }
      return out;
    };
  }

  if (typeof global.btoa === "undefined") {
    global.btoa = function (text) {
      var str = String(text);
      var bytes = new Uint8Array(str.length);
      for (var i = 0; i < str.length; i++) {
        var code = str.charCodeAt(i);
        if (code > 0xff) {
          throw new TypeError(
            "btoa requires a Latin1 string (char code " + code + " at index " + i + ")",
          );
        }
        bytes[i] = code;
      }
      return bytesToBase64(bytes);
    };
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

  // --- Headers (lowercase-name pair list over the URLSearchParams shape) ---

  if (typeof global.Headers === "undefined") {
    var HeadersShim = function (init) {
      this._pairs = [];
      if (init == null) {
        // Nothing to seed.
      } else if (Array.isArray(init)) {
        for (var i = 0; i < init.length; i++) {
          this.append(init[i][0], init[i][1]);
        }
      } else if (typeof init.forEach === "function") {
        var self = this;
        init.forEach(function (value, name) {
          self.append(name, value);
        });
      } else if (typeof init === "object") {
        for (var key in init) {
          if (Object.prototype.hasOwnProperty.call(init, key)) {
            this.append(key, init[key]);
          }
        }
      }
    };
    HeadersShim.prototype.append = function (name, value) {
      this._pairs.push([String(name).toLowerCase(), String(value)]);
    };
    HeadersShim.prototype.set = function (name, value) {
      name = String(name).toLowerCase();
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
    HeadersShim.prototype.get = function (name) {
      name = String(name).toLowerCase();
      for (var i = 0; i < this._pairs.length; i++) {
        if (this._pairs[i][0] === name) return this._pairs[i][1];
      }
      return null;
    };
    HeadersShim.prototype.has = function (name) {
      name = String(name).toLowerCase();
      for (var i = 0; i < this._pairs.length; i++) {
        if (this._pairs[i][0] === name) return true;
      }
      return false;
    };
    HeadersShim.prototype.entries = function () {
      return this._pairs.slice();
    };
    // callback(value, name): the shape normalizeHeaders flattens, so a
    // Headers instance reaches fetch through its existing forEach branch.
    HeadersShim.prototype.forEach = function (callback, thisArg) {
      for (var i = 0; i < this._pairs.length; i++) {
        callback.call(thisArg, this._pairs[i][1], this._pairs[i][0], this);
      }
    };
    global.Headers = HeadersShim;
  }

  // --- Blob, File, and FormData (multipart posts) ---
  //
  // Blob wraps Uint8Array bytes plus a MIME type; File extends it with a
  // name. A FormData generates its boundary once at construction and holds
  // ordered entries; the multipart body is serialized inside bodyToBase64
  // when the FormData is used as a fetch body.

  var AnansiBlob = function (bytes, type) {
    this._bytes = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes == null ? [] : bytes);
    this.type = String(type || "");
  };

  var AnansiFile = function (bytes, name, type) {
    AnansiBlob.call(this, bytes, type);
    this.name = String(name == null ? "file" : name);
  };
  AnansiFile.prototype = Object.create(AnansiBlob.prototype);
  AnansiFile.prototype.constructor = AnansiFile;

  var FormDataShim = function () {
    this._entries = [];
    this._boundary = "----anansi" + Math.random().toString(36).slice(2);
  };
  FormDataShim.prototype.append = function (name, value, filename) {
    if (value instanceof AnansiBlob) {
      this._entries.push({
        name: String(name),
        value: value,
        filename: filename != null ? String(filename) : String(value.name != null ? value.name : "blob"),
      });
    } else if (value != null && typeof value === "object") {
      throw loudUnsupported(
        "FormData value type " +
          (value.constructor && value.constructor.name) +
          " is not supported (use a string or a Blob/File)",
      );
    } else {
      this._entries.push({ name: String(name), value: String(value) });
    }
  };
  FormDataShim.prototype.entries = function () {
    return this._entries.slice();
  };

  if (typeof global.Blob === "undefined") global.Blob = AnansiBlob;
  if (typeof global.File === "undefined") global.File = AnansiFile;
  if (typeof global.FormData === "undefined") global.FormData = FormDataShim;

  // --- AbortController/AbortSignal (JS-side only; see fetch for the limit) ---

  function abortError() {
    var error = new Error("The operation was aborted");
    error.name = "AbortError";
    return error;
  }

  if (typeof global.AbortSignal === "undefined") {
    var AbortSignalShim = function () {
      this.aborted = false;
      this.reason = undefined;
      this.onabort = null;
      this._listeners = [];
    };
    AbortSignalShim.prototype.addEventListener = function (type, callback) {
      if (type === "abort" && typeof callback === "function") {
        this._listeners.push(callback);
      }
    };
    AbortSignalShim.prototype.removeEventListener = function (type, callback) {
      if (type !== "abort") return;
      var next = [];
      for (var i = 0; i < this._listeners.length; i++) {
        if (this._listeners[i] !== callback) next.push(this._listeners[i]);
      }
      this._listeners = next;
    };
    AbortSignalShim.prototype.throwIfAborted = function () {
      if (this.aborted) throw this.reason;
    };
    AbortSignalShim.prototype._abort = function (reason) {
      if (this.aborted) return;
      this.aborted = true;
      this.reason = reason === undefined ? abortError() : reason;
      var listeners = this._listeners.slice();
      this._listeners = [];
      for (var i = 0; i < listeners.length; i++) {
        try {
          listeners[i].call(this);
        } catch (e) {
          /* a listener fault must not stop the remaining listeners */
        }
      }
      if (typeof this.onabort === "function") {
        try {
          this.onabort.call(this);
        } catch (e) {
          /* an onabort fault must not reach the aborting code */
        }
      }
    };
    global.AbortSignal = AbortSignalShim;
  }

  if (typeof global.AbortController === "undefined") {
    global.AbortController = function () {
      this.signal = new global.AbortSignal();
    };
    global.AbortController.prototype.abort = function (reason) {
      this.signal._abort(reason);
    };
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

  // --- Intl is loud (the host provides no i18n APIs) ---
  //
  // The timer-stub pattern generalized to any member access: a Proxy whose
  // get trap throws, so `new Intl.DateTimeFormat(...)` and every other
  // constructor surface fails loudly and uniformly through the
  // marker-to-UnsupportedFeature mapping.

  if (typeof global.Intl === "undefined") {
    global.Intl = new Proxy(
      {},
      {
        get: function () {
          throw loudUnsupported("this host provides no Intl (i18n APIs)");
        },
      },
    );
  }

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

  // Quotes and CR/LF are percent-encoded so a name or filename value can
  // never break out of the Content-Disposition line.
  function dispositionText(text) {
    return String(text).replace(/["\r\n]/g, function (c) {
      var hex = c.charCodeAt(0).toString(16).toUpperCase();
      return "%" + (hex.length < 2 ? "0" + hex : hex);
    });
  }

  function formDataToBase64(form) {
    var encoder = new global.TextEncoder();
    var bytes = [];
    function pushText(text) {
      var encoded = encoder.encode(text);
      for (var i = 0; i < encoded.length; i++) bytes.push(encoded[i]);
    }
    function pushData(data) {
      for (var i = 0; i < data.length; i++) bytes.push(data[i]);
    }
    var entries = form._entries;
    for (var i = 0; i < entries.length; i++) {
      var entry = entries[i];
      var isBlob = entry.value instanceof AnansiBlob;
      pushText("--" + form._boundary + "\r\n");
      pushText('Content-Disposition: form-data; name="' + dispositionText(entry.name) + '"');
      if (isBlob) {
        pushText(
          '; filename="' + dispositionText(entry.filename) + '"' +
            "\r\nContent-Type: " + (entry.value.type || "application/octet-stream"),
        );
      }
      pushText("\r\n\r\n");
      if (isBlob) {
        pushData(entry.value._bytes);
      } else {
        pushText(entry.value);
      }
      pushText("\r\n");
    }
    pushText("--" + form._boundary + "--\r\n");
    return bytesToBase64(new Uint8Array(bytes));
  }

  function bodyToBase64(body) {
    if (body == null) return null;
    if (typeof body === "string") return bytesToBase64(new global.TextEncoder().encode(body));
    if (body instanceof Uint8Array) return bytesToBase64(body);
    if (body instanceof FormDataShim) return formDataToBase64(body);
    throw loudUnsupported(
      "fetch body type " + (body && body.constructor && body.constructor.name) +
        " is not supported (use a string, Uint8Array, or FormData)",
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
    if (options.body instanceof FormDataShim) {
      // A user Content-Type is overridden: the boundary is generated with
      // the FormData instance, and a header without it cannot produce a
      // body the server can parse.
      request.headers["Content-Type"] =
        "multipart/form-data; boundary=" + options.body._boundary;
    }
    var bodyBase64 = bodyToBase64(options.body);
    if (bodyBase64 !== null) request.bodyBase64 = bodyBase64;

    var signal = options.signal;
    if (signal && signal.aborted) {
      return Promise.reject(signal.reason);
    }
    var pending = Promise.resolve(global.__anansiFetch(JSON.stringify(request))).then(
      function (envJson) {
        var envelope = JSON.parse(envJson);
        if (envelope.id !== request.id) {
          throw new Error("fetch envelope id mismatch: " + envelope.id + " != " + request.id);
        }
        if (envelope.err) {
          var fetchError = new Error(envelope.err);
          // Origin evidence for the Kotlin classifier. Non-enumerable so
          // plugin code cannot read it through property iteration.
          Object.defineProperty(fetchError, "anansiFetchBridge", { value: true });
          throw fetchError;
        }
        return makeResponse(envelope);
      },
    );
    if (!signal) return pending;

    // Pinned limitation: abort is JS-side only. The listener below rejects
    // the plugin's awaited promise, but the underlying HTTP transfer is not
    // severed. Real cancellation would need a second multiplexing surface
    // (a __anansiFetchCancel(id) global plus an in-flight call registry in
    // the Kotlin FetchBridge keyed by request id); the observable behavior
    // plugins depend on, the awaited promise settling on abort, is
    // deliverable without it, and the orphaned transfer finishes on its own
    // under the host's HTTP bounds (call timeout, response cap).
    return new Promise(function (resolve, reject) {
      var onAbort = function () {
        reject(signal.reason);
      };
      signal.addEventListener("abort", onAbort);
      pending.then(
        function (response) {
          signal.removeEventListener("abort", onAbort);
          resolve(response);
        },
        function (error) {
          signal.removeEventListener("abort", onAbort);
          reject(error);
        },
      );
    });
  };

  // --- require map (13 entries; @libs/aes resolves to the vendored ciphers) ---

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
      "@libs/aes": vendor.nobleCiphers,
      "@/types/constants": { NovelStatus: NovelStatus, defaultCover: DEFAULT_COVER },
    };
    return function (packageName) {
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
      imageRequestInit: plugin.imageRequestInit == null ? null : plugin.imageRequestInit,
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
  // distinguishable at the Kotlin boundary: `origin` separates fetch-bridge
  // rejections from plugin-thrown errors, and `name` carries the JS error
  // class name as secondary evidence for the classifier.
  global.__anansiCallPluginAsync = function (callId, pluginId, method, argsJson) {
    return global
      .__anansiCallPlugin(pluginId, method, argsJson)
      .then(function (resultJson) {
        return JSON.stringify({ id: callId, ok: resultJson });
      })
      .catch(function (e) {
        var message = e && e.message ? e.message : String(e);
        return JSON.stringify({
          id: callId,
          failed: message,
          name: e && e.name ? e.name : "",
          origin: e && e.anansiFetchBridge ? "fetch-bridge" : "plugin-thrown",
        });
      });
  };

  global.__anansiHostInstalled = true;
})(typeof globalThis !== "undefined" ? globalThis : this);
