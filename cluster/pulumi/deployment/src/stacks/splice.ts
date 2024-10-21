import { config, DeploySvRunbook } from 'splice-pulumi-common';
import {
  mustInstallSplitwell,
  mustInstallValidator1,
} from 'splice-pulumi-common-validator/src/validators';

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
    // TODO(#15579): set refresh to false when version >= 0.2.5 as it no longer drop the split stacks
    createStackCR('validator1', 'validator1', false, reference, {}, true);
  }
  if (mustInstallSplitwell) {
    // TODO(#15579): set refresh to false when version >= 0.2.5 as it no longer drop the split stacks
    createStackCR('splitwell', 'splitwell', false, reference, {}, true);
  }
  createStackCR('infra', 'infra', false, reference);
  createStackCR('canton-network', 'canton-network', false, reference);
}
