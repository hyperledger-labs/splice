// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as fs from 'fs';
import { load } from 'js-yaml';

export const svApiPathPrefix = '/api/sv';

export const publicAudiences = ['validators', 'svs', 'none'] as const;
export type PublicAudience = (typeof publicAudiences)[number];

export const exposedAudiences = ['validators', 'svs'] as const;
export type ExposedAudience = (typeof exposedAudiences)[number];

const httpMethods = ['get', 'put', 'post', 'delete', 'patch', 'head', 'options'];

const publicJvmPackages = ['sv_public', 'sv_public_stream'];

export type SvPublicEndpoint = {
  path: string;
  // HTTP method or `*` for a `$ref`'d path item
  method: string;
  operationId?: string;
  audience: PublicAudience;
};

function isPublicAudience(value: unknown): value is PublicAudience {
  return publicAudiences.includes(value as PublicAudience);
}

/**
 * Parses the SV OpenAPI spec and returns all endpoints that are reachable without
 * authentication together with the external audience they must be exposed to.
 *
 * `x-external-audience` can be declared in two places:
 * - on an operation with a public `x-jvm-package` (see `publicJvmPackages`), where it is mandatory;
 * - on a path item, where it applies to all operations of that path. This is the form to use
 *   for path items that `$ref` a shared, unauthenticated endpoint such as `/version`
 *   (`x-jvm-package: external.common_admin`), which clients call before any other endpoint.
 *
 * Path items without a path-level audience whose operations are not `sv_public` (e.g. the
 * `$ref`'d `/readyz`) are not returned.
 *
 * Throws if an `sv_public` operation does not end up with a valid audience, if an audience is
 * declared on a non-public operation, or if it is declared on both a path item and one of its
 * operations.
 */
export function parseSvPublicEndpoints(openApiContent: string): SvPublicEndpoint[] {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const spec = load(openApiContent) as any;
  const paths = spec?.paths || {};
  const endpoints: SvPublicEndpoint[] = [];
  const errors: string[] = [];
  for (const path of Object.keys(paths)) {
    const pathItem = paths[path] || {};
    const pathAudience = pathItem['x-external-audience'];
    if (pathAudience !== undefined && !isPublicAudience(pathAudience)) {
      errors.push(
        `${path}: path-level x-external-audience must be one of ${publicAudiences.join(
          ', '
        )} but got ${JSON.stringify(pathAudience)}`
      );
      continue;
    }
    const operationMethods = httpMethods.filter(method => pathItem[method]);
    if (operationMethods.length === 0) {
      // A `$ref`'d path item: its operations are defined in the referenced file.
      if (pathAudience !== undefined) {
        endpoints.push({ path, method: '*', audience: pathAudience });
      }
      continue;
    }
    for (const method of operationMethods) {
      const operation = pathItem[method];
      const operationAudience = operation['x-external-audience'];
      const isPublic = publicJvmPackages.includes(operation['x-jvm-package']);
      if (pathAudience !== undefined && operationAudience !== undefined) {
        errors.push(
          `${method.toUpperCase()} ${path}: x-external-audience is declared both on the path item and on the operation`
        );
        continue;
      }
      if (!isPublic && operationAudience !== undefined) {
        errors.push(
          `${method.toUpperCase()} ${path}: x-external-audience is only allowed on endpoints with x-jvm-package in ${publicJvmPackages.join(
            ', '
          )}`
        );
        continue;
      }
      const audience = pathAudience ?? operationAudience;
      if (!isPublic && audience === undefined) {
        continue;
      }
      if (!isPublicAudience(audience)) {
        errors.push(
          `${method.toUpperCase()} ${path}: public endpoints must declare x-external-audience as one of ${publicAudiences.join(
            ', '
          )} but got ${JSON.stringify(audience)}`
        );
        continue;
      }
      endpoints.push({ path, method, operationId: operation.operationId, audience });
    }
  }
  if (errors.length > 0) {
    throw new Error(`Invalid SV OpenAPI public endpoint definitions:\n${errors.join('\n')}`);
  }
  return endpoints;
}

export function toIngressPath(openApiPath: string): string {
  const withWildcards = openApiPath.replace(/\{[^}]+\}/g, '*');
  return `${svApiPathPrefix}${withWildcards}`;
}

export function svPublicIngressPathsByAudience(
  ...openApiContents: string[]
): Record<ExposedAudience, string[]> {
  const endpoints = openApiContents.flatMap(content => parseSvPublicEndpoints(content));
  return Object.fromEntries(
    exposedAudiences.map(audience => [
      audience,
      [
        ...new Set(endpoints.filter(e => e.audience === audience).map(e => toIngressPath(e.path))),
      ].sort(),
    ])
  ) as Record<ExposedAudience, string[]>;
}

export function readSvPublicIngressPathsByAudience(
  ...openApiFiles: string[]
): Record<ExposedAudience, string[]> {
  return svPublicIngressPathsByAudience(
    ...openApiFiles.map(openApiFile => fs.readFileSync(openApiFile, 'utf-8'))
  );
}
