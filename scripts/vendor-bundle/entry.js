// esbuild entry for the Anansi plugin host vendor bundle. Only the real npm
// libraries are bundled; hand-written host behavior lives in host.js.
import * as cheerio from 'cheerio';
import { Parser } from 'htmlparser2';
import dayjs from 'dayjs';
import * as chacha from '@noble/ciphers/chacha.js';
import * as salsa from '@noble/ciphers/salsa.js';
import * as aes from '@noble/ciphers/aes.js';

globalThis.__anansiVendor = {
  cheerio: { load: cheerio.load },
  htmlparser2: { Parser },
  dayjs: dayjs,
  nobleCiphers: { chacha: chacha, salsa: salsa, aes: aes },
};
