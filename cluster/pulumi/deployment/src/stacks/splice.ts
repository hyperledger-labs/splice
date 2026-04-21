// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as semver from 'semver';
import {
  activeVersion,
  CLUSTER_BASENAME,
  CnChartVersion,
  config,
  DecentralizedSynchronizerUpgradeConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  allSvNamesToDeploy,
  svRunbookNodeName,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv/src/dsoConfig';
import {
  deployedValidators,
  validatorRunbookStackName,
} from '@lfdecentralizedtrust/splice-pulumi-common-validator';
import { deploymentConf } from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/config';
import {
  GitFluxRef,
  StackFromRef,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/flux-source';
import {
  createStackCR,
  EnvRefs,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/operator/stack';

function isVersionAtLeastOrSnapshot(version: CnChartVersion, minVersion: string): boolean {
  if (version.type === 'local') {
    return true;
  }
  return semver.gte(version.version, minVersion) || version.version.startsWith(minVersion);
}

export function* getSpliceStacksFromMainReference(): Generator<StackFromRef> {
  if (deploymentConf.projectsToDeploy.has('sv-runbook')) {
    yield { project: 'sv-runbook', stack: `sv-runbook.${CLUSTER_BASENAME}` };
  }
  if (deploymentConf.projectsToDeploy.has('multi-validator')) {
    yield { project: 'multi-validator', stack: `multi-validator.${CLUSTER_BASENAME}` };
  }
  if (deploymentConf.projectsToDeploy.has('validator-runbook')) {
    for (const validator of deployedValidators) {
      yield {
        project: 'validator-runbook',
        stack: `${validatorRunbookStackName(validator)}.${CLUSTER_BASENAME}`,
      };
    }
  }
  if (deploymentConf.projectsToDeploy.has('validator1')) {
    yield { project: 'validator1', stack: `validator1.${CLUSTER_BASENAME}` };
  }
  if (deploymentConf.projectsToDeploy.has('splitwell')) {
    yield { project: 'splitwell', stack: `splitwell.${CLUSTER_BASENAME}` };
  }
  if (deploymentConf.projectsToDeploy.has('infra')) {
    yield { project: 'infra', stack: `infra.${CLUSTER_BASENAME}` };
  }
  if (
    deploymentConf.projectsToDeploy.has('observability') &&
    isVersionAtLeastOrSnapshot(activeVersion, '0.6.0')
  ) {
    yield { project: 'observability', stack: `observability.${CLUSTER_BASENAME}` };
  }
  if (deploymentConf.projectsToDeploy.has('canton-network')) {
    yield { project: 'canton-network', stack: `canton-network.${CLUSTER_BASENAME}` };
  }
  if (
    deploymentConf.projectsToDeploy.has('sv') &&
    (DecentralizedSynchronizerUpgradeConfig.active.enableLogicalSynchronizerDeploymentMode ||
      DecentralizedSynchronizerUpgradeConfig.active.migrateParticipantsFromSvCantonToSv)
  ) {
    for (const sv of allSvNamesToDeploy) {
      yield { project: 'sv', stack: `sv.${sv}.${CLUSTER_BASENAME}` };
    }
  }
}

export function installSpliceStacks(
  reference: GitFluxRef,
  envRefs: EnvRefs,
  namespace: string,
  gcpSecret: k8s.core.v1.Secret
): void {
  if (deploymentConf.projectsToDeploy.has('sv-runbook')) {
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
  if (deploymentConf.projectsToDeploy.has('multi-validator')) {
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
  if (deploymentConf.projectsToDeploy.has('validator1')) {
    createStackCR('validator1', 'validator1', namespace, false, reference, envRefs, gcpSecret);
  }
  if (deploymentConf.projectsToDeploy.has('splitwell')) {
    createStackCR('splitwell', 'splitwell', namespace, false, reference, envRefs, gcpSecret);
  }
  if (deploymentConf.projectsToDeploy.has('infra')) {
    createStackCR('infra', 'infra', namespace, false, reference, envRefs, gcpSecret);
  }
  if (
    deploymentConf.projectsToDeploy.has('observability') &&
    isVersionAtLeastOrSnapshot(activeVersion, '0.6.0')
  ) {
    createStackCR(
      'observability',
      'observability',
      namespace,
      false,
      reference,
      envRefs,
      gcpSecret
    );
  }
  if (deploymentConf.projectsToDeploy.has('canton-network')) {
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
  installSvStacks(reference, envRefs, namespace, gcpSecret);
}

function installSvStacks(
  reference: GitFluxRef,
  envRefs: EnvRefs,
  namespace: string,
  gcpSecret: k8s.core.v1.Secret
): void {
  if (
    deploymentConf.projectsToDeploy.has('sv') &&
    (DecentralizedSynchronizerUpgradeConfig.active.enableLogicalSynchronizerDeploymentMode ||
      DecentralizedSynchronizerUpgradeConfig.active.migrateParticipantsFromSvCantonToSv)
  ) {
    for (const sv of allSvNamesToDeploy) {
      createStackCR(
        `sv.${sv}`,
        'sv',
        namespace,
        sv === svRunbookNodeName && config.envFlag('SUPPORTS_SV_RUNBOOK_RESET'),
        reference,
        envRefs,
        gcpSecret,
        {
          SPLICE_SV: sv,
        }
      );
    }
  }
}
