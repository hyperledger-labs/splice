import { config, DeploySvRunbook } from 'splice-pulumi-common';
import { mustInstallValidator1 } from 'splice-pulumi-common-validator/src/validators';

import { GitFluxRef } from '../flux';
import { createStackCR } from './stack';

export function installSpliceStacks(reference: GitFluxRef): void {
  if (DeploySvRunbook) {
    createStackCR(
      'sv-runbook',
      'sv-runbook',
      config.envFlag('SUPPORTS_SV_RUNBOOK_RESET'),
      reference
    );
  }
  if (config.envFlag('SPLICE_DEPLOY_MULTI_VALIDATOR', false)) {
    createStackCR('multi-validator', 'multi-validator', false, reference);
  }
  if (config.envFlag('SPLICE_DEPLOY_VALIDATOR_RUNBOOK', false)) {
    createStackCR(
      'validator-runbook',
      'validator-runbook',
      config.envFlag('SUPPORTS_VALIDATOR_RUNBOOK_RESET'),
      reference
    );
  }
  if (mustInstallValidator1) {
    createStackCR('validator1', 'validator1', false, reference);
  }
  createStackCR('infra', 'infra', false, reference);
  createStackCR('canton-network', 'canton-network', false, reference);
}
