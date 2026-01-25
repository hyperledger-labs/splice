// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { spliceEnvConfig } from './envConfig';

export const GcpProject = spliceEnvConfig.requireEnv('CLOUDSDK_CORE_PROJECT');
export const GcpRegion = spliceEnvConfig.requireEnv('CLOUDSDK_COMPUTE_REGION');
export const GcpZone = spliceEnvConfig.optionalEnv('CLOUDSDK_COMPUTE_ZONE');

export const ClusterBasename = spliceEnvConfig.requireEnv('GCP_CLUSTER_BASENAME');
