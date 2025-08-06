// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { config } from '@lfdecentralizedtrust/splice-pulumi-common';

import { GcpProject } from './gcp-project';

const gcpProjectId = pulumi.getStack();

const GCP_PROJECT = config.requireEnv('CLOUDSDK_CORE_PROJECT');
if (!GCP_PROJECT) {
  throw new Error('CLOUDSDK_CORE_PROJECT is undefined');
}
if (gcpProjectId !== GCP_PROJECT) {
  throw new Error(
    `The stack name (${gcpProjectId}) does not match CLOUDSDK_CORE_PROJECT (${GCP_PROJECT}) -- check your environment or active stack`
  );
}

function main() {
  return new GcpProject(gcpProjectId);
}

main();
