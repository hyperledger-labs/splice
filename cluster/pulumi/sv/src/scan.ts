// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  CLUSTER_HOSTNAME,
  CnInput,
  DecentralizedSynchronizerMigrationConfig,
  ExactNamespace,
  InstalledHelmChart,
  envoyClientIpHeaderEnvVar,
  failOnAppVersionMismatch,
  getAdditionalJvmOptions,
  installSpliceHelmChart,
  persistentHeapDumpsPvc,
  sanitizedForPostgres,
  spliceInstanceNames,
} from '@canton-network/splice-pulumi-common';
import {
  CantonBftSynchronizerNode,
  DecentralizedSynchronizerNode,
  SynchronizerNodes,
} from '@canton-network/splice-pulumi-common-sv';
import { SvConfig, svsConfig } from '@canton-network/splice-pulumi-common-sv/src/config';
import { Postgres } from '@canton-network/splice-pulumi-common/src/postgres';
import { installRateLimits } from '@canton-network/splice-pulumi-common/src/ratelimit/rateLimit';
import { scanRateLimitEnvVars } from '@canton-network/splice-pulumi-common/src/ratelimit/spliceRateLimits';
import { Resource } from '@pulumi/pulumi';

import { persistenceConfig } from './persistence';

export function installScan(
  xns: ExactNamespace,
  config: SvConfig,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  dependsOn: CnInput<Resource>[],
  synchronizerNodes: SynchronizerNodes,
  postgres: Postgres
): InstalledHelmChart {
  const { active, participant } = synchronizerNodes;
  const scanDbName = `scan_${sanitizedForPostgres(config.nodeName)}`;

  const cantonBftConfigFor = (node: DecentralizedSynchronizerNode) => {
    return {
      cantonBft: {
        p2pUrl: (node as unknown as CantonBftSynchronizerNode).externalSequencerP2pAddress,
      },
    };
  };

  const synchronizerValues = {
    synchronizers: {
      current: {
        sequencer: active.namespaceInternalSequencerAddress,
        mediator: active.namespaceInternalMediatorAddress,
        ...cantonBftConfigFor(active),
      },
      ...(synchronizerNodes.upgrade
        ? {
            successor: {
              sequencer: synchronizerNodes.upgrade.namespaceInternalSequencerAddress,
              mediator: synchronizerNodes.upgrade.namespaceInternalMediatorAddress,
              ...cantonBftConfigFor(synchronizerNodes.upgrade),
            },
          }
        : {}),
      ...(synchronizerNodes.legacy
        ? {
            legacy: {
              sequencer: synchronizerNodes.legacy.namespaceInternalSequencerAddress,
              mediator: synchronizerNodes.legacy.namespaceInternalMediatorAddress,
              ...cantonBftConfigFor(synchronizerNodes.legacy),
            },
          }
        : {}),
    },
  };

  const scanValues = {
    ...spliceInstanceNames,
    metrics: {
      enable: true,
    },
    isFirstSv: config.isFirstSv,
    persistence: persistenceConfig(postgres, scanDbName),
    additionalJvmOptions: getAdditionalJvmOptions(config.scanApp?.additionalJvmOptions),
    failOnAppVersionMismatch: failOnAppVersionMismatch,
    participantAddress: participant.internalClusterAddress,
    ...(config.onboarding.type == 'join-with-key'
      ? { sponsorScanUrl: config.onboarding.sponsorScanUrl }
      : {}),
    ...synchronizerValues,
    enablePostgresMetrics: true,
    logLevel: config.logging?.appsLogLevel,
    apiRequestLogLevel: config.logging?.apiRequestLogLevel,
    logAsyncFlush: config.logging?.appsAsync,
    additionalEnvVars: (config.scanApp?.additionalEnvVars || [])
      .concat([envoyClientIpHeaderEnvVar('canton.scan-apps.scan-app')])
      .concat(scanRateLimitEnvVars()),
    resources: config.scanApp?.resources,
    ...(config.bulkStorageBuckets
      ? {
          bulkStorage: {
            staging: {
              region: config.bulkStorageBuckets.staging.region,
              bucketName: config.bulkStorageBuckets.staging.bucket.name,
              endpoint: 'https://storage.googleapis.com', // gcs endpoint for s3
              secretName: config.bulkStorageBuckets.staging.secret.metadata.name,
            },
            committed: {
              region: config.bulkStorageBuckets.committed.region,
              bucketName: config.bulkStorageBuckets.committed.bucket.name,
              endpoint: 'https://storage.googleapis.com', // gcs endpoint for s3
              secretName: config.bulkStorageBuckets.committed.secret.metadata.name,
            },
          },
        }
      : {}),
    publicUrl: publicScanUrl(config),
    pvc: persistentHeapDumpsPvc(),
  };

  if (svsConfig?.scan?.externalRateLimits) {
    installRateLimits(xns.logicalName, 'scan-app', 5012, svsConfig.scan.externalRateLimits);
  }

  return installSpliceHelmChart(xns, 'scan', 'splice-scan', scanValues, config.version, {
    // TODO(#893) if possible, don't require parallel start of sv app and scan when using CantonBft
    dependsOn: dependsOn.concat(active.dependencies).concat([postgres]),
  });
}

export function publicScanUrl(config: SvConfig): string {
  return `https://scan.${config.ingressName}.${CLUSTER_HOSTNAME}`;
}

export function internalScanUrl(config: SvConfig): string {
  return `http://scan-app.${config.nodeName}:5012`;
}
