// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// Shared constants for multivalidator stuff across pulumi projects
import { config } from './config';

export const numNodesPerInstance = 10;
export const numInstances = +(config.optionalEnv('MULTIVALIDATOR_SIZE') || '0');

export function generatePortSequence(
  basePort: number,
  numNodes: number,
  ports: { name?: string; id: number }[]
): { name: string; port: number }[] {
  return Array.from({ length: numNodes }, (_, i) =>
    ports.map(p => ({ name: p.name ? `${p.name}-${i}` : `${i}`, port: basePort + i * 100 + p.id }))
  ).flat();
}
