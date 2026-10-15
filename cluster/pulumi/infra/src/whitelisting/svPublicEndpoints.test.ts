// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as fs from 'fs';
import * as path from 'path';
import { describe, expect, test } from '@jest/globals';

import {
  exposedAudiences,
  parseSvPublicEndpoints,
  publicAudiences,
  svPublicIngressPathsByAudience,
  toIngressPath,
} from './svPublicEndpoints';

const svOpenApiFiles = [
  '../../../../../apps/sv/src/main/openapi/sv-internal.yaml',
  '../../../../../apps/sv/src/main/openapi/sv-stream-server.yaml',
].map(file => path.join(__dirname, file));

describe('the SV OpenAPI specs', () => {
  const contents = svOpenApiFiles.map(file => fs.readFileSync(file, 'utf-8'));

  test('declare an x-external-audience for every public endpoint', () => {
    const endpoints = contents.flatMap(content => parseSvPublicEndpoints(content));
    expect(endpoints.length).toBeGreaterThan(0);
    endpoints.forEach(endpoint => {
      expect(publicAudiences).toContain(endpoint.audience);
    });
  });

  test('exposes the expected paths per audience', () => {
    const paths = svPublicIngressPathsByAudience(...contents);
    expect(paths['validators']).toContain('/api/sv/v0/onboard/validator');
    expect(paths['svs']).toContain('/api/sv/v0/migration-id');
    expect(paths['svs']).toContain('/api/sv/v0/onboard/sv/status/*');
    // defined in sv-stream-server.yaml rather than in sv-internal.yaml
    expect(paths['svs']).toContain('/api/sv/v0/onboard/sv/party-migration/authorize');
    // clients check the version before any other call, so /version must be reachable
    expect(paths['validators']).toContain('/api/sv/version');
    const allPaths = exposedAudiences.flatMap(audience => paths[audience]);
    // endpoints with an audience of none must not be whitelisted
    expect(allPaths).not.toContain('/api/sv/v0/admin/domain/cometbft/status');
    expect(allPaths).not.toContain('/api/sv/v0/admin/domain/cometbft/json-rpc');
    // non-public endpoints must not be whitelisted
    expect(allPaths).not.toContain('/api/sv/v0/admin/sv/votes');
    expect(allPaths).not.toContain('/api/sv/readyz');
    expect(allPaths).not.toContain('/api/sv/livez');
    expect(allPaths).not.toContain('/api/sv/status');
    // deprecated endpoints must not be whitelisted
    expect(paths['validators']).not.toContain('/api/sv/v0/dso');
  });
});

describe('parseSvPublicEndpoints', () => {
  const spec = (extra: string) => `
openapi: 3.0.0
paths:
  /v0/foo:
    get:
      x-jvm-package: sv_public
${extra}
      operationId: getFoo
      responses:
        "200":
          description: ok
`;

  test('fails if an sv_public endpoint has no x-external-audience', () => {
    expect(() => parseSvPublicEndpoints(spec(''))).toThrow(/must declare x-external-audience/);
  });

  test('fails on an unknown x-external-audience', () => {
    expect(() => parseSvPublicEndpoints(spec('      x-external-audience: everyone'))).toThrow(
      /must declare x-external-audience/
    );
  });

  test('accepts a valid x-external-audience', () => {
    expect(parseSvPublicEndpoints(spec('      x-external-audience: validators'))).toEqual([
      { path: '/v0/foo', method: 'get', operationId: 'getFoo', audience: 'validators' },
    ]);
  });

  test('accepts an audience of none but does not whitelist the endpoint', () => {
    const content = spec('      x-external-audience: none');
    expect(parseSvPublicEndpoints(content)).toEqual([
      { path: '/v0/foo', method: 'get', operationId: 'getFoo', audience: 'none' },
    ]);
    const paths = svPublicIngressPathsByAudience(content);
    expect(exposedAudiences.flatMap(audience => paths[audience])).toEqual([]);
  });

  test('rejects x-external-audience on non-public endpoints', () => {
    const nonPublic = `
openapi: 3.0.0
paths:
  /v0/foo:
    get:
      x-jvm-package: sv_operator
      x-external-audience: validators
      operationId: getFoo
`;
    expect(() => parseSvPublicEndpoints(nonPublic)).toThrow(/only allowed on endpoints/);
  });
});

describe('path-level x-external-audience', () => {
  const refSpec = (extra: string) => `
openapi: 3.0.0
paths:
  /version:
${extra}    $ref: "common.yaml#/paths/~1version"
`;

  test('exposes a $ref path item that declares an audience', () => {
    const content = refSpec('    x-external-audience: validators\n');
    expect(parseSvPublicEndpoints(content)).toEqual([
      { path: '/version', method: '*', audience: 'validators' },
    ]);
    expect(svPublicIngressPathsByAudience(content)['validators']).toEqual(['/api/sv/version']);
  });

  test('ignores a $ref path item without an audience', () => {
    const content = refSpec('');
    expect(parseSvPublicEndpoints(content)).toEqual([]);
    expect(svPublicIngressPathsByAudience(content)['validators']).toEqual([]);
  });

  test('fails on an unknown path-level audience', () => {
    expect(() => parseSvPublicEndpoints(refSpec('    x-external-audience: everyone\n'))).toThrow(
      /must be one of/
    );
  });

  test('applies a path-level audience to all inline operations', () => {
    const content = `
openapi: 3.0.0
paths:
  /v0/foo:
    x-external-audience: svs
    get:
      x-jvm-package: sv_public
      operationId: getFoo
    post:
      x-jvm-package: sv_public
      operationId: postFoo
`;
    expect(parseSvPublicEndpoints(content)).toEqual([
      { path: '/v0/foo', method: 'get', operationId: 'getFoo', audience: 'svs' },
      { path: '/v0/foo', method: 'post', operationId: 'postFoo', audience: 'svs' },
    ]);
  });

  test('rejects declaring the audience both on the path item and on an operation', () => {
    const content = `
openapi: 3.0.0
paths:
  /v0/foo:
    x-external-audience: svs
    get:
      x-jvm-package: sv_public
      x-external-audience: svs
      operationId: getFoo
`;
    expect(() => parseSvPublicEndpoints(content)).toThrow(/both on the path item and/);
  });
});

test('toIngressPath replaces path parameters with a wildcard', () => {
  expect(toIngressPath('/v0/onboard/sv/status/{candidate_party_id_or_name}')).toBe(
    '/api/sv/v0/onboard/sv/status/*'
  );
  expect(toIngressPath('/v0/onboard/validator')).toBe('/api/sv/v0/onboard/validator');
});
