import { config } from 'cn-pulumi-common';

import { createStackCR } from './stacks/stack';

if (config.envFlag('CN_DEPLOY_SV_RUNBOOK', false)) {
  createStackCR('sv-runbook');
}
if (config.envFlag('CN_DEPLOY_MULTI_VALIDATOR', false)) {
  createStackCR('multi-validator');
}
if (config.envFlag('CN_DEPLOY_VALIDATOR_RUNBOOK', false)) {
  createStackCR('validator-runbook');
}

export const infraStack = createStackCR('infra');
export const cantonNetworkStack = createStackCR('canton-network');
