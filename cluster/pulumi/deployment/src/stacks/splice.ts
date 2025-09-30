// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import {
  CLUSTER_BASENAME,
  config,
  DeploySvRunbook,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { deployedValidators } from '@lfdecentralizedtrust/splice-pulumi-common-validator';
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
    ret.push({ project: 'sv-runbook', stack: `sv-runbook.${CLUSTER_BASENAME}` });
  }
  if (config.envFlag('SPLICE_DEPLOY_MULTI_VALIDATOR', false)) {
    ret.push({ project: 'multi-validator', stack: `multi-validator.${CLUSTER_BASENAME}` });
  }
  deployedValidators.forEach(validator => {
    ret.push({ project: 'validator-runbook', stack: `${validator}.${CLUSTER_BASENAME}` });
  });
  if (mustInstallValidator1) {
    ret.push({ project: 'validator1', stack: `validator1.${CLUSTER_BASENAME}` });
  }
  if (mustInstallSplitwell) {
    ret.push({ project: 'splitwell', stack: `splitwell.${CLUSTER_BASENAME}` });
  }
  ret.push({ project: 'infra', stack: `infra.${CLUSTER_BASENAME}` });
  ret.push({ project: 'canton-network', stack: `canton-network.${CLUSTER_BASENAME}` });
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
