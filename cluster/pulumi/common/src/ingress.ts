// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
export function ingressPort(
  name: string,
  port: number
): { name: string; port: number; targetPort: number; protocol: string } {
  return {
    name: name,
    port: port,
    targetPort: port,
    protocol: 'TCP',
  };
}
