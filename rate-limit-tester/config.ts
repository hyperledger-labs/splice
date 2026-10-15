// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export type Protocol = 'http' | 'grpc';

export type Component = {
  name: string;
  protocol: Protocol;
  endpoint: string;
  maxRequestsPerIp: number;
  windowSeconds: number;
};

export function loadConfig(path: string | undefined): Component[] {
  if (!path) {
    throw new Error('pass the config file as -e CONFIG=$(pwd)/config.json');
  }
  const raw: Record<string, Record<string, unknown>> = JSON.parse(open(path));
  const components = Object.entries(raw).map(([name, fields]) => parseComponent(name, fields));
  if (components.length === 0) {
    throw new Error(`${path} defines no components`);
  }
  return components;
}

function parseComponent(name: string, fields: Record<string, unknown>): Component {
  const { protocol, endpoint, maxRequestsPerIp, windowSeconds } = fields;
  const invalid = (field: string, expected: string) =>
    new Error(`${name}.${field}: expected ${expected}`);

  if (protocol !== 'http' && protocol !== 'grpc') throw invalid('protocol', '"http" or "grpc"');
  if (typeof endpoint !== 'string' || endpoint === '')
    throw invalid('endpoint', 'a non-empty string');
  if (
    typeof maxRequestsPerIp !== 'number' ||
    !Number.isInteger(maxRequestsPerIp) ||
    maxRequestsPerIp <= 0
  ) {
    throw invalid('maxRequestsPerIp', 'a positive integer');
  }
  if (typeof windowSeconds !== 'number' || windowSeconds <= 0) {
    throw invalid('windowSeconds', 'a positive number');
  }
  return { name, protocol, endpoint, maxRequestsPerIp, windowSeconds };
}
