// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export function generatePortSequence(
  basePort: number,
  numNodes: number,
  ports: { name?: string; id: number }[]
): { name: string; port: number }[] {
  return Array.from({ length: numNodes }, (_, i) =>
    ports.map(p => ({ name: p.name ? `${p.name}-${i}` : `${i}`, port: basePort + i * 100 + p.id }))
  ).flat();
}
