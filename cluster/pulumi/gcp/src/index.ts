// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';

import { GcpProject } from './gcpProject';

export const gcpProjectId = pulumi.getStack();

/*eslint no-process-env: "off"*/
const GCP_PROJECT = process.env.CLOUDSDK_CORE_PROJECT;
if (!GCP_PROJECT) {
  throw new Error('CLOUDSDK_CORE_PROJECT is undefined');
}
if (gcpProjectId !== GCP_PROJECT) {
  throw new Error(
    'The stack name does not match CLOUDSDK_CORE_PROJECT -- check your environment or active stack'
  );
}

const provider = new gcp.Provider(`provider-${gcpProjectId}`, {
  project: gcpProjectId,
});

new GcpProject(gcpProjectId, { gcpProjectId }, { provider });
