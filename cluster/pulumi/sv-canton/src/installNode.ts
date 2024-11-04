import {
  DecentralizedSynchronizerUpgradeConfig,
  DomainMigrationIndex,
  exactNamespace,
  Auth0Client,
} from 'splice-pulumi-common';
import {
  coreSvsToDeploy,
  installCantonComponents,
  InstalledMigrationSpecificSv,
  sv1Config,
  svConfigs,
  svRunbookConfig,
} from 'splice-pulumi-common-sv';

export function installNode(
  migrationId: DomainMigrationIndex,
  sv: string,
  auth0Client: Auth0Client
): InstalledMigrationSpecificSv | undefined {
  const svConfig = svConfigs.concat([svRunbookConfig]).find(config => {
    return config.nodeName === sv;
  });
  if (svConfig === undefined) {
    throw new Error(`No sv config found for ${sv}`);
  }
  const nodeConfig = svConfig!;
  const isCoreSv = nodeConfig.nodeName !== svRunbookConfig.nodeName;
  const isFirstSv = nodeConfig.nodeName === sv1Config.nodeName;
  return installCantonComponents(
    // namespace lifecycle managed by the main canton-network stack
    exactNamespace(nodeConfig.nodeName, true, true),
    migrationId,
    auth0Client,
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
}
