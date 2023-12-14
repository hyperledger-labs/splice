require('esbuild').build({
  entryPoints: ['./src/test/generate-load.ts'],
  sourcemap: true,
  bundle: true,
  target: 'node18',
  platform: 'node',
  outfile: 'dist/generate-load.js',
  external: ['k6*', 'https://jslib.*'],
});
