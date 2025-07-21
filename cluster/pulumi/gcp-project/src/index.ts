// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { config } from 'splice-pulumi-common';

import { GcpProject } from './gcp-project';

const gcpProjectId = pulumi.getStack();

const configsDir = config.requireEnv('GCP_PROJECT_CONFIGS_DIR');

const GCP_PROJECT = config.requireEnv('CLOUDSDK_CORE_PROJECT');
if (!GCP_PROJECT) {
  throw new Error('CLOUDSDK_CORE_PROJECT is undefined');
}
if (gcpProjectId !== GCP_PROJECT) {
  throw new Error(
    `The stack name (${gcpProjectId}) does not match CLOUDSDK_CORE_PROJECT (${GCP_PROJECT}) -- check your environment or active stack`
  );
}

// Typically the SA that authorized CircleCI runs get to use, and external to the project set up here.
// TODO use config.yaml somehow instead of env var
const authorizedServiceAccountEmail = config.optionalEnv('AUTHORIZED_SERVICE_ACCOUNT');
const authorizedServiceAccountConfig = authorizedServiceAccountEmail
  ? (() => {
      const pulumiKeyringProjectId = config.requireEnv('PULUMI_BACKEND_GCPKMS_PROJECT');
      if (!pulumiKeyringProjectId) {
        throw new Error('PULUMI_BACKEND_GCPKMS_PROJECT is undefined');
      }
      const pulumiKeyringRegion = config.requireEnv('CLOUDSDK_COMPUTE_REGION');
      if (!pulumiKeyringRegion) {
        throw new Error('CLOUDSDK_COMPUTE_REGION is undefined');
      }
      return {
        serviceAccountEmail: authorizedServiceAccountEmail,
        pulumiKeyringProjectId,
        pulumiKeyringRegion,
      };
    })()
  : (() => {
      console.warn(
        'AUTHORIZED_SERVICE_ACCOUNT is not set; this is only fine for a cluster never touched by CircleCI flows.'
      );
      return undefined;
    })();

function main() {
  return new GcpProject(gcpProjectId, configsDir);
}

main();
