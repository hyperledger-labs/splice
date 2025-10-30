// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import { CLUSTER_BASENAME, config } from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  deployedValidators,
  validatorRunbookStackName,
} from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import {
  GitFluxRef,
  StackFromRef,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/flux-source';
import {
  createStackCR,
  EnvRefs,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/stack';

import { deploymentConf } from '../config';

export function getSpliceStacksFromMainReference(): StackFromRef[] {
  const ret: StackFromRef[] = [];
  if (deploymentConf.projectWhitelist.has('sv-runbook')) {
    ret.push({ project: 'sv-runbook', stack: `sv-runbook.${CLUSTER_BASENAME}` });
  }
  if (deploymentConf.projectWhitelist.has('multi-validator')) {
    ret.push({ project: 'multi-validator', stack: `multi-validator.${CLUSTER_BASENAME}` });
  }
  if (deploymentConf.projectWhitelist.has('validator-runbook')) {
    deployedValidators.forEach(validator => {
      ret.push({
        project: 'validator-runbook',
        stack: `${validatorRunbookStackName(validator)}.${CLUSTER_BASENAME}`,
      });
    });
  }
  if (deploymentConf.projectWhitelist.has('validator1')) {
    ret.push({ project: 'validator1', stack: `validator1.${CLUSTER_BASENAME}` });
  }
  if (deploymentConf.projectWhitelist.has('splitwell')) {
    ret.push({ project: 'splitwell', stack: `splitwell.${CLUSTER_BASENAME}` });
  }
  if (deploymentConf.projectWhitelist.has('infra')) {
    ret.push({ project: 'infra', stack: `infra.${CLUSTER_BASENAME}` });
  }
  if (deploymentConf.projectWhitelist.has('canton-network')) {
    ret.push({ project: 'canton-network', stack: `canton-network.${CLUSTER_BASENAME}` });
  }
  return ret;
}

export function installSpliceStacks(
  reference: GitFluxRef,
  envRefs: EnvRefs,
  namespace: string,
  gcpSecret: k8s.core.v1.Secret
): void {
  if (deploymentConf.projectWhitelist.has('sv-runbook')) {
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
  if (deploymentConf.projectWhitelist.has('multi-validator')) {
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
  if (deploymentConf.projectWhitelist.has('validator1')) {
    createStackCR('validator1', 'validator1', namespace, false, reference, envRefs, gcpSecret);
  }
  if (deploymentConf.projectWhitelist.has('splitwell')) {
    createStackCR('splitwell', 'splitwell', namespace, false, reference, envRefs, gcpSecret);
  }
  if (deploymentConf.projectWhitelist.has('infra')) {
    createStackCR('infra', 'infra', namespace, false, reference, envRefs, gcpSecret);
  }
  if (deploymentConf.projectWhitelist.has('canton-network')) {
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
}
