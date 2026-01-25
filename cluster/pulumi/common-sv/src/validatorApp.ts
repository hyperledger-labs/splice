// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  CLUSTER_HOSTNAME,
  DecentralizedSynchronizerMigrationConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common';

import { EnvVarConfig, SingleSvConfiguration } from './singleSvConfig';

export function valuesForSvValidatorApp(
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  config: SingleSvConfiguration
): {
  decentralizedSynchronizerUrl?: string;
  useSequencerConnectionsFromScan?: boolean;
  additionalEnvVars: EnvVarConfig[];
} {
  const decentralizedSynchronizerUrl = `https://sequencer-${decentralizedSynchronizerMigrationConfig.active.id}.sv-2.${CLUSTER_HOSTNAME}`;
  const bftSequencerConnection = !config.participant || config.participant.bftSequencerConnection;

  const participantPruningConfig = config.pruning?.participant?.enabled
    ? [
        {
          name: 'ADDITIONAL_CONFIG_PARTICIPANT_PRUNING',
          value: `canton.validator-apps.validator_backend.participant-pruning-schedule {
                    cron = "${config.pruning.participant.cron}"
                    max-duration = "${config.pruning.participant.maxDuration}"
                    retention = "${config.pruning.participant.retentionPeriod}"
                  }`,
        },
      ]
    : [];
  // if you add a top level field here that is an object make sure to handle merging it in the caller
  return {
    ...(bftSequencerConnection
      ? {}
      : {
          decentralizedSynchronizerUrl: decentralizedSynchronizerUrl,
          useSequencerConnectionsFromScan: false,
        }),
    additionalEnvVars: [
      ...(bftSequencerConnection
        ? []
        : [
            {
              name: 'ADDITIONAL_CONFIG_NO_BFT_SEQUENCER_CONNECTION',
              value:
                'canton.validator-apps.validator_backend.disable-sv-validator-bft-sequencer-connection = true',
            },
          ]),
      ...(config.validatorApp?.additionalEnvVars || []),
    ].concat(participantPruningConfig),
  };
}
