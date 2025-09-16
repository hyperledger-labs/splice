// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { CLUSTER_BASENAME } from '@lfdecentralizedtrust/splice-pulumi-common';
import { gitRepoForRef } from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/flux-source';
import { createEnvRefs } from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/stack';

import { operatorDeploymentConfig } from '../../common/src/operator/config';
import { flux } from './flux';
import { namespace } from './namespace';
import { installDeploymentStack } from './stacks/deployment';

const deploymentStackReference = gitRepoForRef(
  'deployment',
  operatorDeploymentConfig.reference,
  [{ project: 'deployment', stack: `deployment.${CLUSTER_BASENAME}` }],
  false, // no notifications since this typically follows `main` and is too noisy
  [flux]
);
const envRefs = createEnvRefs('operator-env', namespace.logicalName);
installDeploymentStack(deploymentStackReference, envRefs);
