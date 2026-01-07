import * as pulumi from '@pulumi/pulumi';
import { DecentralizedSynchronizerMigrationConfig } from '@lfdecentralizedtrust/splice-pulumi-common';

import { EnvVarConfig, SingleSvConfiguration } from './singleSvConfig';
import {
  CometbftSynchronizerNode,
  DecentralizedSynchronizerNode,
} from './synchronizer/decentralizedSynchronizerNode';

export function valuesForSvApp(
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  config: SingleSvConfiguration & { cometBftGovernanceKey?: unknown },
  decentralizedSynchronizer?: DecentralizedSynchronizerNode
): {
  domain: object;
  additionalEnvVars: EnvVarConfig[];
  cometBFT?: object;
} {
  const useCantonBft = decentralizedSynchronizerMigrationConfig.active.sequencer.enableBftSequencer;
  const bftSequencerConnectionEnvVars =
    !config.participant || config.participant.bftSequencerConnection
      ? []
      : [
          {
            name: 'ADDITIONAL_CONFIG_NO_BFT_SEQUENCER_CONNECTION',
            value: 'canton.sv-apps.sv.bft-sequencer-connection = false',
          },
        ];

  const mediatorPruningConfig = config.pruning?.mediator?.enabled
    ? [
        {
          name: 'ADDITIONAL_CONFIG_MEDIATOR_PRUNING',
          value: `canton.sv-apps.sv.local-synchronizer-node.mediator.pruning {
                    cron = "${config.pruning.mediator.cron}"
                    max-duration = "${config.pruning.mediator.maxDuration}"
                    retention = "${config.pruning.mediator.retentionPeriod}"
                  }`,
        },
      ]
    : [];
  const additionalEnvVars = (config.svApp?.additionalEnvVars || [])
    .concat(bftSequencerConnectionEnvVars)
    .concat(mediatorPruningConfig);
  // if you add a top level field here that is an object make sure to handle merging it in the caller
  return {
    ...(useCantonBft
      ? {}
      : {
          cometBFT: {
            enabled: true,
            ...(decentralizedSynchronizer
              ? {
                  connectionUri: pulumi.interpolate`http://${(decentralizedSynchronizer as unknown as CometbftSynchronizerNode).cometbftRpcServiceName}:26657`,
                }
              : {}),
            ...(config.cometBftGovernanceKey ? { externalGovernanceKey: true } : {}),
          },
        }),
    domain: {
      ...(config.pruning?.sequencer ? { sequencerPruningConfig: config.pruning.sequencer } : {}),
      ...(useCantonBft
        ? {
            enableBftSequencer: true,
          }
        : {}),
    },
    additionalEnvVars,
  };
}
