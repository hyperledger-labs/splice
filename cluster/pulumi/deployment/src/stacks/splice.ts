import { config, DeploySvRunbook } from 'splice-pulumi-common';

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

  createStackCR('infra', 'infra', false, reference);
  createStackCR('canton-network', 'canton-network', false, reference);
}
