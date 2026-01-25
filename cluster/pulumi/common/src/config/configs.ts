// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { spliceConfig } from './config';
import { spliceEnvConfig } from './envConfig';

// This flag determines whether to split postgres instances per app, or have one per namespace.
// By default, we split instances on CloudSQL (where we expect longer-living environments, thus want to support backup&recovery),
// but not on k8s-deployed postgres (where we optimize for faster deployment).
// One can force splitting them by setting SPLIT_POSTGRES_INSTANCES to true.
export const SplitPostgresInstances =
  spliceEnvConfig.envFlag('SPLIT_POSTGRES_INSTANCES') ||
  spliceConfig.pulumiProjectConfig.cloudSql.enabled;
