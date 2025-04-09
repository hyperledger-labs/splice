import {
  CLUSTER_BASENAME,
  config,
  DeploySvRunbook,
  DeployValidatorRunbook,
} from 'splice-pulumi-common';
import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from 'splice-pulumi-common-validator/src/validators';
import { GitFluxRef, StackFromRef } from 'splice-pulumi-common/src/operator/flux-source';
import { createStackCR, EnvRefs } from 'splice-pulumi-common/src/operator/stack';

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

export function installSpliceStacks(reference: GitFluxRef, envRefs: EnvRefs): void {
  if (DeploySvRunbook) {
    createStackCR(
      'sv-runbook',
      'sv-runbook',
      config.envFlag('SUPPORTS_SV_RUNBOOK_RESET'),
      reference,
      envRefs
    );
  }
  if (config.envFlag('SPLICE_DEPLOY_MULTI_VALIDATOR', false)) {
    createStackCR('multi-validator', 'multi-validator', false, reference, envRefs);
  }
  if (DeployValidatorRunbook) {
    createStackCR(
      'validator-runbook',
      'validator-runbook',
      config.envFlag('SUPPORTS_VALIDATOR_RUNBOOK_RESET'),
      reference,
      envRefs
    );
  }
  if (mustInstallValidator1) {
    createStackCR('validator1', 'validator1', false, reference, envRefs);
  }
  if (mustInstallSplitwell) {
    createStackCR('splitwell', 'splitwell', false, reference, envRefs);
  }
  createStackCR('infra', 'infra', false, reference, envRefs);
  createStackCR('canton-network', 'canton-network', false, reference, envRefs);
}
