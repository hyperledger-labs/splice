import * as pulumi from '@pulumi/pulumi';
import {
  Auth0ClientType,
  getAuth0Config,
  DecentralizedSynchronizerUpgradeConfig,
  DomainMigrationIndex,
  exactNamespace,
} from 'splice-pulumi-common';
import {
  coreSvsToDeploy,
  installCantonComponents,
  sv1Config,
  svConfigs,
  svRunbookConfig,
} from 'splice-pulumi-common-sv';

export function installNode(
  migrationId: DomainMigrationIndex,
  sv: string,
  loadAuth0Cache: boolean = true
): pulumi.Output<void> {
  const svConfig = svConfigs.concat([svRunbookConfig]).find(config => {
    return config.nodeName === sv;
  });
  if (svConfig === undefined) {
    throw new Error(`No sv config found for ${sv}`);
  }
  const auth0FetchOutput = getAuth0Config(
    sv === 'sv' ? Auth0ClientType.RUNBOOK : Auth0ClientType.MAINSTACK
  );
  const nodeConfig = svConfig!;
  return auth0FetchOutput.apply(async auth0Fetch => {
    console.error(`Installing node ${sv} for migration ${migrationId}`);
    if (loadAuth0Cache) {
      await auth0Fetch.loadAuth0Cache();
    }
    const isCoreSv = nodeConfig.nodeName !== svRunbookConfig.nodeName;
    const isFirstSv = nodeConfig.nodeName === sv1Config.nodeName;
    installCantonComponents(
      // namespace lifecycle managed by the main canton-network stack
      exactNamespace(nodeConfig.nodeName, false, true),
      migrationId,
      auth0Fetch,
      {
        onboardingName: nodeConfig.onboardingName,
        isFirstSv: isFirstSv,
        isCoreSv: isCoreSv,
      },
      DecentralizedSynchronizerUpgradeConfig,
      {
        nodeConfigs: {
          self: {
            ...nodeConfig.cometBft,
            nodeName: nodeConfig.nodeName,
          },
          sv1: {
            ...sv1Config.cometBft,
            nodeName: sv1Config.nodeName,
          },
          peers:
            isCoreSv && !isFirstSv
              ? coreSvsToDeploy
                  .filter(config => config.nodeName !== nodeConfig.nodeName)
                  .map(config => {
                    return {
                      ...config.cometBft,
                      nodeName: config.nodeName,
                    };
                  })
              : [],
        },
      }
    );
    if (loadAuth0Cache) {
      await auth0Fetch.saveAuth0Cache();
    }
  });
}
