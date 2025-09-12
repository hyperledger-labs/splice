// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  CLUSTER_BASENAME,
  config,
  DeploySvRunbook,
  DeployValidatorRunbook,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from '@lfdecentralizedtrust/splice-pulumi-common-validator/src/validators';
import {
  GitFluxRef,
  StackFromRef,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/flux-source';
import {
  createStackCR,
  EnvRefs,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/stack';

export function getSpliceStacksFromMainReference(): StackFromRef[] {
  const ret: StackFromRef[] = [];
  if (DeploySvRunbook) {
    ret.push({ project: 'sv-runbook', stack: CLUSTER_BASENAME });
  }
  if (config.envFlag('SPLICE_DEPLOY_MULTI_VALIDATOR', false)) {
    ret.push({ project: 'multi-validator', stack: CLUSTER_BASENAME });
  }
  if (DeployValidatorRunbook) {
    ret.push({ project: 'validator-runbook', stack: CLUSTER_BASENAME });
  }
  if (mustInstallValidator1) {
    ret.push({ project: 'validator1', stack: CLUSTER_BASENAME });
  }
  if (mustInstallSplitwell) {
    ret.push({ project: 'splitwell', stack: CLUSTER_BASENAME });
  }
  ret.push({ project: 'infra', stack: CLUSTER_BASENAME });
  ret.push({ project: 'canton-network', stack: CLUSTER_BASENAME });
  return ret;
}

export function installSpliceStacks(
  reference: GitFluxRef,
  envRefs: EnvRefs,
  namespace: string,
  gcpSecret: k8s.core.v1.Secret
): void {
  if (DeploySvRunbook) {
    createStackCR(
      'sv-runbook',
      'sv-runbook',
      namespace,
      config.envFlag('SUPPORTS_SV_RUNBOOK_RESET'),
      reference,
      envRefs,
      gcpSecret
    );
  }
  if (config.envFlag('SPLICE_DEPLOY_MULTI_VALIDATOR', false)) {
    createStackCR(
      'multi-validator',
      'multi-validator',
      namespace,
      false,
      reference,
      envRefs,
      gcpSecret,
      {},
      []
    );
  }
  if (DeployValidatorRunbook) {
    createStackCR(
      'validator-runbook',
      'validator-runbook',
      namespace,
      config.envFlag('SUPPORTS_VALIDATOR_RUNBOOK_RESET'),
      reference,
      envRefs,
      gcpSecret,
      {
        SPLICE_VALIDATOR_RUNBOOK_VALIDATOR_NAME: 'validator-runbook',
      }
    );
  }
  if (mustInstallValidator1) {
    createStackCR('validator1', 'validator1', namespace, false, reference, envRefs, gcpSecret);
  }
  if (mustInstallSplitwell) {
    createStackCR('splitwell', 'splitwell', namespace, false, reference, envRefs, gcpSecret);
  }
  createStackCR('infra', 'infra', namespace, false, reference, envRefs, gcpSecret);
  createStackCR(
    'canton-network',
    'canton-network',
    namespace,
    false,
    reference,
    envRefs,
    gcpSecret
  );
}
