import { config } from 'cn-pulumi-common';

import { createStackCR } from './stacks/stack';

if (config.envFlag('SPLICE_DEPLOY_SV_RUNBOOK', false)) {
  createStackCR('sv-runbook', config.envFlag('SUPPORTS_SV_RUNBOOK_RESET'));
}
if (config.envFlag('SPLICE_DEPLOY_MULTI_VALIDATOR', false)) {
  createStackCR('multi-validator', false);
}
if (config.envFlag('SPLICE_DEPLOY_VALIDATOR_RUNBOOK', false)) {
  createStackCR('validator-runbook', config.envFlag('SUPPORTS_VALIDATOR_RUNBOOK_RESET'));
}

export const infraStack = createStackCR('infra', false);
export const cantonNetworkStack = createStackCR('canton-network', false);
