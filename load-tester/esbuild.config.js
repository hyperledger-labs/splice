// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
require('esbuild').build({
  entryPoints: ['./src/test/generate-load.ts'],
  sourcemap: true,
  bundle: true,
  target: 'es2020',
  platform: 'node',
  outfile: 'dist/generate-load.js',
  external: ['k6*', 'https://jslib.*'],
});
