import { config, DeploySvRunbook } from 'splice-pulumi-common';
import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from 'splice-pulumi-common-validator/src/validators';
import { GitFluxRef } from 'splice-pulumi-common/src/operator/flux-source';
import { createStackCR, EnvRefs } from 'splice-pulumi-common/src/operator/stack';

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
  if (config.envFlag('SPLICE_DEPLOY_VALIDATOR_RUNBOOK', false)) {
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
