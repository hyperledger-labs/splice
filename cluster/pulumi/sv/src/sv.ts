// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as postgres from '@canton-network/splice-pulumi-common/src/postgres';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  activeVersion,
  ansDomainPrefix,
  Auth0Client,
  btoa,
  CLUSTER_BASENAME,
  CLUSTER_HOSTNAME,
  CnInput,
  config as envConfig,
  DecentralizedSynchronizerUpgradeConfig,
  ExactNamespace,
  fetchAndInstallParticipantBootstrapDump,
  imagePullSecret,
  InstalledHelmChart,
  installSpliceHelmChart,
  installSvAppSecrets,
  installValidatorOnboardingSecret,
  isDevNet,
  svCometBftGovernanceKeyFromSecret,
  svCometBftGovernanceKeySecret,
  SvIdKey,
  svKeyFromSecret,
  svOnboardingPollingInterval,
  svValidatorTopupConfig,
} from '@canton-network/splice-pulumi-common';
import {
  approvedSvIdentities,
  coreSvsToDeploy,
  initialRound,
  installScanBulkStorage,
  installLoopback,
  SingleSvConfiguration,
  StaticSvConfig,
  SynchronizerNodes,
} from '@canton-network/splice-pulumi-common-sv';
import { SvConfig } from '@canton-network/splice-pulumi-common-sv/src/config';
import { installInfo } from '@canton-network/splice-pulumi-common-sv/src/info';
import { readBackupConfig } from '@canton-network/splice-pulumi-common-validator/src/backup';
import {
  mustInstallSplitwell,
  mustInstallValidator1,
  splitwellOnboarding,
  standaloneValidatorOnboarding,
  validator1Onboarding,
} from '@canton-network/splice-pulumi-common-validator/src/validators';
import { installBucketSecret } from '@canton-network/splice-pulumi-common/src/buckets';
import { spliceConfig } from '@canton-network/splice-pulumi-common/src/config/config';
import { SplitPostgresInstances } from '@canton-network/splice-pulumi-common/src/config/configs';
import { topologySnapshotConfig } from '@canton-network/splice-pulumi-common/src/topology-snapshot';
import { Resource } from '@pulumi/pulumi';
import pick from 'lodash/pick';

import { installAppsPostgres } from './persistence';
import { installScan } from './scan';
import { installSvApp } from './svApp';
import { installValidator } from './validator';

export async function installSvApps(
  xns: ExactNamespace,
  staticConfig: StaticSvConfig,
  dynamicConfig: SingleSvConfiguration,
  auth0Client: Auth0Client,
  extraDependsOn: CnInput<Resource>[] = []
): Promise<InstalledSv> {
  const nodeName = staticConfig.nodeName;
  const [sv1StaticConfig] = coreSvsToDeploy;
  const isFoundingSv = nodeName === sv1StaticConfig.nodeName;
  const disableOnboardingParticipantPromotionDelay = envConfig.envFlag(
    'DISABLE_ONBOARDING_PARTICIPANT_PROMOTION_DELAY',
    false
  );
  const backupConfig = await readBackupConfig();
  const config: SvConfig = {
    isFirstSv: isFoundingSv,
    ...pick(staticConfig, [
      'nodeName',
      'ingressName',
      'onboardingName',
      'validatorWalletUser',
      'auth0ValidatorAppName',
      'auth0SvAppName',
      'sweep',
    ]),
    onboarding: isFoundingSv
      ? {
          type: 'found-dso',
          sv1SvRewardWeightBps:
            approvedSvIdentities().find(identity => identity.name == sv1StaticConfig.onboardingName)
              ?.rewardWeightBps ?? 10_000,
          roundZeroDuration: envConfig.optionalEnv('ROUND_ZERO_DURATION'),
          initialRound: initialRound?.toString(),
        }
      : {
          type: 'join-with-key',
          sponsorApiUrl: `http://sv-app.sv-1:5014`,
          sponsorScanUrl: `http://scan-app.sv-1:5012`,
          keys: svKeyFromSecret(
            staticConfig.svIdKeySecretName ?? `${nodeName.replaceAll('-', '')}-id`
          ),
        },
    auth0Client,
    expectedValidatorOnboardings: isFoundingSv
      ? [
          ...(function* () {
            if (mustInstallSplitwell) {
              yield splitwellOnboarding;
            }
            if (mustInstallValidator1) {
              yield validator1Onboarding;
            }
            if (standaloneValidatorOnboarding !== undefined) {
              yield standaloneValidatorOnboarding;
            }
          })(),
        ]
      : [],
    isDevNet,
    periodicBackupConfig: backupConfig.periodicBackupConfig
      ? {
          ...backupConfig.periodicBackupConfig,
          location: {
            ...backupConfig.periodicBackupConfig.location,
            prefix:
              backupConfig.periodicBackupConfig.location.prefix ||
              `${CLUSTER_BASENAME}/${xns.logicalName}`,
          },
        }
      : undefined,
    identitiesBackupLocation: {
      ...backupConfig.identitiesBackupLocation,
      prefix: `${CLUSTER_BASENAME}/${xns.logicalName}`,
    },
    bootstrappingDumpConfig: backupConfig.bootstrappingDumpConfig,
    bulkStorageBuckets: dynamicConfig.scanApp?.bulkStorage
      ? installScanBulkStorage(xns, dynamicConfig.scanApp.bulkStorage)
      : undefined,
    topupConfig: svValidatorTopupConfig,
    splitPostgresInstances: SplitPostgresInstances,
    disableOnboardingParticipantPromotionDelay,
    onboardingPollingInterval: svOnboardingPollingInterval,
    cometBftGovernanceKey:
      dynamicConfig.participant?.kms !== undefined
        ? svCometBftGovernanceKeyFromSecret(
            staticConfig.cometBftGovernanceKeySecretName ??
              `${nodeName.replaceAll('-', '')}-cometbft-governance-key`
          )
        : undefined,
    initialRound: initialRound?.toString(),
    version: dynamicConfig.versionOverride ?? activeVersion,
    ...dynamicConfig,
  };

  const periodicTopologySnapshotConfig = config.periodicSnapshots?.topology
    ? await topologySnapshotConfig(
        config.periodicSnapshots?.topology,
        `${CLUSTER_BASENAME}/${xns.logicalName}`
      )
    : undefined;

  const loopback = installLoopback(xns);
  const imagePullDeps = imagePullSecret(xns);

  const auth0Secrets: CnInput<pulumi.Resource>[] = await installSvAppSecrets(
    xns,
    config.auth0Client
  );

  const identitiesBackupConfigSecret = installBucketSecret(
    xns,
    config.identitiesBackupLocation.bucket
  );

  const topologySnapshotConfigSecret = periodicTopologySnapshotConfig
    ? installBucketSecret(xns, periodicTopologySnapshotConfig.location.bucket)
    : undefined;
  const backupConfigSecret: pulumi.Resource | undefined = config.periodicBackupConfig
    ? config.periodicBackupConfig.location.bucket != config.identitiesBackupLocation.bucket
      ? installBucketSecret(xns, config.periodicBackupConfig.location.bucket)
      : identitiesBackupConfigSecret
    : undefined;

  const participantBootstrapDumpSecret: pulumi.Resource | undefined = config.bootstrappingDumpConfig
    ? await fetchAndInstallParticipantBootstrapDump(xns, config.bootstrappingDumpConfig)
    : undefined;

  const dependsOn: CnInput<pulumi.Resource>[] = auth0Secrets
    .concat(
      config.onboarding.type == 'join-with-key'
        ? installSvKeySecret(xns, config.onboarding.keys)
        : []
    )
    .concat(
      config.onboarding.type == 'join-with-key' &&
        config.onboarding.sponsorRelease !== undefined &&
        spliceConfig.pulumiProjectConfig.interAppsDependencies
        ? [config.onboarding.sponsorRelease]
        : []
    )
    .concat(
      config.expectedValidatorOnboardings.map(onboarding =>
        installValidatorOnboardingSecret(xns, onboarding.name, onboarding.secret)
      )
    )
    .concat([identitiesBackupConfigSecret])
    .concat(backupConfigSecret ? [backupConfigSecret] : [])
    .concat(topologySnapshotConfigSecret ? [topologySnapshotConfigSecret] : [])
    .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : [])
    .concat(loopback)
    .concat(imagePullDeps)
    .concat(
      config.cometBftGovernanceKey
        ? svCometBftGovernanceKeySecret(xns, config.cometBftGovernanceKey)
        : []
    )
    .concat(
      config.bulkStorageBuckets
        ? [
            config.bulkStorageBuckets.staging.secret,
            config.bulkStorageBuckets.staging.bucket,
            config.bulkStorageBuckets.committed.secret,
            config.bulkStorageBuckets.committed.bucket,
          ]
        : []
    )
    .concat(extraDependsOn);

  const appsPostgres = await installAppsPostgres(xns, config);

  const canton = new SynchronizerNodes(DecentralizedSynchronizerUpgradeConfig, config.ingressName);

  const svApp = installSvApp(
    DecentralizedSynchronizerUpgradeConfig,
    { ...config, periodicTopologySnapshotConfig },
    xns,
    dependsOn,
    appsPostgres,
    canton
  );

  const scan = installScan(
    xns,
    config,
    DecentralizedSynchronizerUpgradeConfig,
    dependsOn,
    canton,
    appsPostgres
  );

  installInfo(
    xns,
    `info.${config.ingressName}.${CLUSTER_HOSTNAME}`,
    'cluster-ingress/cn-http-gateway',
    DecentralizedSynchronizerUpgradeConfig,
    `http://scan-app.${config.nodeName}:5012`,
    scan,
    config.version
  );

  const validatorApp = await installValidator(
    appsPostgres,
    xns,
    DecentralizedSynchronizerUpgradeConfig,
    config,
    backupConfigSecret,
    canton,
    svApp,
    scan
  );

  const ingress = installSpliceHelmChart(
    xns,
    'ingress-sv',
    'splice-cluster-ingress-runbook',
    {
      withSvIngress: true,
      ingress: {
        decentralizedSynchronizer: {
          migrationIds: DecentralizedSynchronizerUpgradeConfig.runningMigrations().map(x =>
            x.id.toString()
          ),
        },
      },
      spliceDomainNames: {
        nameServiceDomain: ansDomainPrefix,
      },
      cluster: {
        hostname: CLUSTER_HOSTNAME,
        svNamespace: xns.logicalName,
        svIngressName: config.ingressName,
      },
      rateLimit: {
        scan: {
          enable: false,
        },
      },
    },
    config.version,
    { dependsOn: [xns.ns] }
  );

  return {
    namespace: xns,
    nodeName: config.nodeName,
    canton,
    validatorApp,
    svApp,
    scan,
    ingress,
    appsPostgres,
  };
}

function installSvKeySecret(xns: ExactNamespace, keys: CnInput<SvIdKey>): k8s.core.v1.Secret[] {
  const legacySecretName = 'cn-app-sv-key';
  const secretName = 'splice-app-sv-key';

  const data = pulumi.output(keys).apply(ks => {
    return {
      public: btoa(ks.publicKey),
      private: btoa(ks.privateKey),
    };
  });

  return [
    new k8s.core.v1.Secret(
      `cn-app-${xns.logicalName}-key`,
      {
        metadata: {
          name: legacySecretName,
          namespace: xns.logicalName,
        },
        type: 'Opaque',
        data: data,
      },
      {
        dependsOn: [xns.ns],
      }
    ),
    new k8s.core.v1.Secret(
      `splice-app-${xns.logicalName}-key`,
      {
        metadata: {
          name: secretName,
          namespace: xns.logicalName,
        },
        type: 'Opaque',
        data: data,
      },
      {
        dependsOn: [xns.ns],
      }
    ),
  ];
}

export type InstalledSv = {
  namespace: ExactNamespace;
  nodeName: string;
  validatorApp: Resource;
  svApp: InstalledHelmChart;
  scan: InstalledHelmChart;
  canton: SynchronizerNodes;
  ingress: Resource;
  appsPostgres: postgres.Postgres;
};
