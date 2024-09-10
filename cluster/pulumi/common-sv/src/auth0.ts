import * as pulumi from '@pulumi/pulumi';
import { Output } from '@pulumi/pulumi';
import {
  Auth0ClusterConfig,
  Auth0Fetch,
  config,
  infraStack,
  isMainNet,
} from 'splice-pulumi-common';

export enum Auth0ClientType {
  RUNBOOK,
  MAINSTACK,
}

export function getAuth0Config(clientType: Auth0ClientType): Output<Auth0Fetch> {
  const auth0ClusterCfg = infraStack.requireOutput('auth0') as pulumi.Output<Auth0ClusterConfig>;
  switch (clientType) {
    case Auth0ClientType.RUNBOOK:
      if (!auth0ClusterCfg.svRunbook) {
        throw new Error('missing sv runbook auth0 output');
      }
      return auth0ClusterCfg.svRunbook.apply(cfg => {
        if (!cfg) {
          throw new Error('missing sv runbook auth0 output');
        }
        cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET');
        return new Auth0Fetch(cfg);
      });
    case Auth0ClientType.MAINSTACK:
      if (isMainNet) {
        if (!auth0ClusterCfg.mainnet) {
          throw new Error('missing mainNet auth0 output');
        }
        return auth0ClusterCfg.mainnet.apply(cfg => {
          if (!cfg) {
            throw new Error('missing mainNet auth0 output');
          }
          cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_MAIN_MANAGEMENT_API_CLIENT_SECRET');
          return new Auth0Fetch(cfg);
        });
      } else {
        if (!auth0ClusterCfg.cantonNetwork) {
          throw new Error('missing cantonNetwork auth0 output');
        }
        return auth0ClusterCfg.cantonNetwork.apply(cfg => {
          if (!cfg) {
            throw new Error('missing cantonNetwork auth0 output');
          }
          cfg.auth0MgtClientSecret = config.requireEnv('AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET');
          return new Auth0Fetch(cfg);
        });
      }
  }
}
