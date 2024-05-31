import { config } from 'cn-pulumi-common';

import { createStackCR } from './stacks/stack';

if (config.envFlag('CN_DEPLOY_SV_RUNBOOK', false)) {
  createStackCR('sv-runbook', true);
}
if (config.envFlag('CN_DEPLOY_MULTI_VALIDATOR', false)) {
  createStackCR('multi-validator', false);
}
if (config.envFlag('CN_DEPLOY_VALIDATOR_RUNBOOK', false)) {
  createStackCR('validator-runbook', false);
}

export const infraStack = createStackCR('infra', false);
export const cantonNetworkStack = createStackCR('canton-network', false);
