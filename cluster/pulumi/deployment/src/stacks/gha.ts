// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import { CLUSTER_BASENAME } from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  GitFluxRef,
  StackFromRef,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/flux-source';
import {
  createStackCR,
  EnvRefs,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/stack';

import { deploymentConf } from '../config';

export function getGithubActionsStackFromMainReference(): Array<StackFromRef> {
  if (deploymentConf.projectWhitelist.has('gha')) {
    return [{ project: 'gha', stack: `gha.${CLUSTER_BASENAME}` }];
  } else {
    return [];
  }
}

export function installGithubActionsStack(
  reference: GitFluxRef,
  envRefs: EnvRefs,
  namespace: string,
  gcpSecret: k8s.core.v1.Secret
): void {
  if (deploymentConf.projectWhitelist.has('gha')) {
    createStackCR('gha', 'gha', namespace, false, reference, envRefs, gcpSecret);
  }
}
