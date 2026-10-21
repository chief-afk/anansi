// Regenerates the committed vendor bundle at
// app/src/main/assets/pluginhost/vendor.js. Run from this directory:
//   npm install && npm run build
// App builds never run this; Node is only needed when bumping the bundled
// libraries (a recorded build decision).
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const outfile = fileURLToPath(
  new URL('../../app/src/main/assets/pluginhost/vendor.js', import.meta.url),
);

const result = await build({
  entryPoints: [fileURLToPath(new URL('./entry.js', import.meta.url))],
  outfile,
  bundle: true,
  format: 'iife',
  target: ['es2020'],
  platform: 'browser',
  minify: true,
  legalComments: 'inline',
  logLevel: 'info',
});

if (result.errors.length > 0) {
  process.exitCode = 1;
}
