// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as postgres from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  activeVersion,
  ansDomainPrefix,
  appsAffinityAndTolerations,
  BackupConfig,
  btoa,
  ChartValues,
  CLUSTER_BASENAME,
  CLUSTER_HOSTNAME,
  CnInput,
  daContactPoint,
  DecentralizedSynchronizerMigrationConfig,
  ExactNamespace,
  exactNamespace,
  failOnAppVersionMismatch,
  fetchAndInstallParticipantBootstrapDump,
  getAdditionalJvmOptions,
  getSvAppApiAudience,
  imagePullSecret,
  initialPackageConfigJson,
  initialSynchronizerFeesConfig,
  installBootstrapDataBucketSecret,
  InstalledHelmChart,
  installSpliceHelmChart,
  installSvAppSecrets,
  installValidatorOnboardingSecret,
  networkWideConfig,
  participantBootstrapDumpSecretName,
  PersistenceConfig,
  sanitizedForPostgres,
  spliceInstanceNames,
  svCometBftGovernanceKeySecret,
  SvIdKey,
  svUserIds,
  validatorOnboardingSecretName,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  approvedSvIdentities,
  CantonBftSynchronizerNode,
  configForSv,
  DecentralizedSynchronizerNode,
  InstalledMigrationSpecificSv,
  installSvLoopback,
  SvParticipant,
  valuesForSvApp,
  valuesForValidatorApp,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { SvConfig, svsConfig } from '@lfdecentralizedtrust/splice-pulumi-common-sv/src/config';
import { installValidatorApp } from '@lfdecentralizedtrust/splice-pulumi-common-validator/src/validator';
import { spliceConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import { initialAmuletPrice } from '@lfdecentralizedtrust/splice-pulumi-common/src/initialAmuletPrice';
import { Postgres } from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';
import { topologySnapshotConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/topology-snapshot';
import { Resource } from '@pulumi/pulumi';

import {
  delegatelessAutomationExpectedTaskDuration,
  delegatelessAutomationExpiredRewardCouponBatchSize,
} from '../../common/src/automation';
import { installRateLimits } from '../../common/src/ratelimit/rateLimit';
import { configureScanBigQuery } from './bigQuery';
import { buildCrossStackCantonDependencies } from './canton';
import { installInfo } from './info';

export function installSvKeySecret(
  xns: ExactNamespace,
  keys: CnInput<SvIdKey>
): k8s.core.v1.Secret[] {
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
  validatorApp: Resource;
  svApp: InstalledHelmChart;
  scan: InstalledHelmChart;
  decentralizedSynchronizer: DecentralizedSynchronizerNode;
  participant: SvParticipant;
  ingress: Resource;
};

export async function installSvNode(
  baseConfig: SvConfig,
  decentralizedSynchronizerUpgradeConfig: DecentralizedSynchronizerMigrationConfig,
  extraDependsOn: CnInput<Resource>[] = []
): Promise<InstalledSv> {
  const xns = exactNamespace(baseConfig.nodeName, true);
  const loopback = installSvLoopback(xns);
  const imagePullDeps = imagePullSecret(xns);

  const auth0Secrets: CnInput<pulumi.Resource>[] = await installSvAppSecrets(
    xns,
    baseConfig.auth0Client
  );

  const periodicBackupConfig: BackupConfig | undefined = baseConfig.periodicBackupConfig
    ? {
        ...baseConfig.periodicBackupConfig,
        location: {
          ...baseConfig.periodicBackupConfig.location,
          prefix:
            baseConfig.periodicBackupConfig.location.prefix ||
            `${CLUSTER_BASENAME}/${xns.logicalName}`,
        },
      }
    : undefined;

  const svConfig = configForSv(baseConfig.nodeName);
  const periodicTopologySnapshotConfig: BackupConfig | undefined = svConfig.periodicSnapshots
    ?.topology
    ? await topologySnapshotConfig(
        svConfig.periodicSnapshots?.topology,
        `${CLUSTER_BASENAME}/${xns.logicalName}`
      )
    : undefined;

  const identitiesBackupLocation = {
    ...baseConfig.identitiesBackupLocation,
    prefix: baseConfig.identitiesBackupLocation.prefix || `${CLUSTER_BASENAME}/${xns.logicalName}`,
  };

  const config = { ...baseConfig, periodicBackupConfig, identitiesBackupLocation };

  const identitiesBackupConfigSecret = installBootstrapDataBucketSecret(
    xns,
    config.identitiesBackupLocation.bucket
  );

  const topologySnapshotConfigSecret = periodicTopologySnapshotConfig
    ? installBootstrapDataBucketSecret(xns, periodicTopologySnapshotConfig.location.bucket)
    : undefined;

  const backupConfigSecret: pulumi.Resource | undefined = config.periodicBackupConfig
    ? config.periodicBackupConfig.location.bucket != config.identitiesBackupLocation.bucket
      ? installBootstrapDataBucketSecret(xns, config.periodicBackupConfig.location.bucket)
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
        config.onboarding.sponsorRelease &&
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
    .concat(extraDependsOn);

  const defaultPostgres = config.splitPostgresInstances
    ? undefined
    : postgres.installPostgres(
        xns,
        'postgres',
        'postgres',
        activeVersion,
        spliceConfig.pulumiProjectConfig.cloudSql,
        false,
        {
          logicalDecoding: !!baseConfig.scanBigQuery,
        }
      );

  const appsPostgres =
    defaultPostgres ||
    postgres.installPostgres(
      xns,
      `cn-apps-pg`,
      `cn-apps-pg`,
      activeVersion,
      spliceConfig.pulumiProjectConfig.cloudSql,
      true,
      {
        logicalDecoding: !!baseConfig.scanBigQuery,
      }
    );

  const canton = buildCrossStackCantonDependencies(
    decentralizedSynchronizerUpgradeConfig,
    {
      name: config.nodeName,
      onboardingName: config.onboardingName,
      nodeConfigs: {
        ...config.nodeConfigs,
        self: { ...config.cometBft, nodeName: config.nodeName },
      },
    },
    config
  );

  const svApp = installSvApp(
    decentralizedSynchronizerUpgradeConfig,
    { ...config, periodicTopologySnapshotConfig },
    xns,
    dependsOn,
    appsPostgres,
    canton.participant,
    canton.decentralizedSynchronizer
  );

  const scan = installScan(
    xns,
    config,
    decentralizedSynchronizerUpgradeConfig,
    dependsOn,
    canton.decentralizedSynchronizer,
    svApp,
    canton.participant,
    appsPostgres
  );

  installInfo(
    xns,
    `info.${config.ingressName}.${CLUSTER_HOSTNAME}`,
    'cluster-ingress/cn-http-gateway',
    decentralizedSynchronizerUpgradeConfig,
    `http://scan-app.${config.nodeName}:5012`,
    scan
  );

  if (baseConfig.scanBigQuery && appsPostgres instanceof postgres.CloudPostgres) {
    configureScanBigQuery(appsPostgres, baseConfig.scanBigQuery, scan);
  }

  const validatorApp = await installValidator(
    appsPostgres,
    xns,
    decentralizedSynchronizerUpgradeConfig,
    baseConfig,
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
          migrationIds: decentralizedSynchronizerUpgradeConfig
            .runningMigrations()
            .map(x => x.id.toString()),
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
    activeVersion,
    { dependsOn: [xns.ns] }
  );

  return { ...canton, validatorApp, svApp, scan, ingress };
}

function persistenceConfig(postgresDb: postgres.Postgres, dbName: string): PersistenceConfig {
  const dbNameO = pulumi.Output.create(dbName);
  return {
    host: postgresDb.address,
    databaseName: dbNameO,
    secretName: postgresDb.secretName,
    schema: dbNameO,
    user: pulumi.Output.create('cnadmin'),
    port: pulumi.Output.create(5432),
    postgresName: postgresDb.instanceName,
  };
}

async function installValidator(
  postgres: Postgres,
  xns: ExactNamespace,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  svConfig: SvConfig,
  backupConfigSecret: Resource | undefined,
  sv: InstalledMigrationSpecificSv,
  svApp: Resource,
  scan: Resource
) {
  const validatorDbName = `validator_${sanitizedForPostgres(svConfig.nodeName)}`;
  const commonValidatorAppValues = valuesForValidatorApp(
    decentralizedSynchronizerMigrationConfig,
    svConfig
  );

  return await installValidatorApp({
    xns,
    ...commonValidatorAppValues,
    migration: {
      id: decentralizedSynchronizerMigrationConfig.active.id,
    },
    validatorWalletUsers: svUserIds(svConfig.auth0Client.getCfg()).apply(ids =>
      ids.concat(svConfig.validatorWalletUser ? [svConfig.validatorWalletUser] : [])
    ),
    dependencies: sv.participant.asDependencies,
    disableAllocateLedgerApiUserParty: true,
    topupConfig: svConfig.topupConfig,
    backupConfig:
      svConfig.periodicBackupConfig && backupConfigSecret
        ? {
            config: svConfig.periodicBackupConfig,
            secret: backupConfigSecret,
          }
        : undefined,
    persistenceConfig: persistenceConfig(postgres, validatorDbName),
    extraDependsOn: spliceConfig.pulumiProjectConfig.interAppsDependencies
      ? [svApp, postgres, scan]
      : [postgres],
    svValidator: true,
    participantAddress: sv.participant.internalClusterAddress,
    scanAddress: internalScanUrl(svConfig),
    auth0Client: svConfig.auth0Client,
    auth0ValidatorAppName: svConfig.auth0ValidatorAppName,
    sweep: svConfig.sweep,
    nodeIdentifier: svConfig.onboardingName,
    logLevel: svConfig.logging?.appsLogLevel,
    logAsync: svConfig.logging?.appsAsync,
    additionalJvmOptions: svConfig.validatorApp?.additionalJvmOptions || '',
    resources: svConfig.validatorApp?.resources,
  });
}

function internalScanUrl(config: SvConfig): pulumi.Output<string> {
  return pulumi.interpolate`http://scan-app.${config.nodeName}:5012`;
}

function installSvApp(
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  config: SvConfig,
  xns: ExactNamespace,
  dependsOn: CnInput<Resource>[],
  postgres: Postgres,
  participant: SvParticipant,
  decentralizedSynchronizer: DecentralizedSynchronizerNode
) {
  const svDbName = `sv_${sanitizedForPostgres(config.nodeName)}`;
  const commonSvAppValues = valuesForSvApp(
    decentralizedSynchronizerMigrationConfig,
    config,
    decentralizedSynchronizer
  );

  const svValues = {
    ...commonSvAppValues,
    ...decentralizedSynchronizerMigrationConfig.migratingNodeConfig(),
    ...spliceInstanceNames,
    onboardingType: config.onboarding.type,
    onboardingName: config.onboardingName,
    onboardingFoundingSvRewardWeightBps:
      config.onboarding.type == 'found-dso' ? config.onboarding.sv1SvRewardWeightBps : undefined,
    onboardingRoundZeroDuration:
      config.onboarding.type == 'found-dso' ? config.onboarding.roundZeroDuration : undefined,
    initialSynchronizerFeesConfig:
      config.onboarding.type == 'found-dso' ? initialSynchronizerFeesConfig : undefined,
    initialPackageConfigJson:
      config.onboarding.type == 'found-dso' ? initialPackageConfigJson : undefined,
    initialRound:
      config.onboarding.type == 'found-dso' ? config.onboarding.initialRound : undefined,
    initialAmuletPrice: initialAmuletPrice,
    disableOnboardingParticipantPromotionDelay: config.disableOnboardingParticipantPromotionDelay,
    decentralizedSynchronizerUrl:
      config.onboarding.type == 'found-dso'
        ? undefined
        : decentralizedSynchronizer.sv1InternalSequencerAddress,
    domain:
      // defaults for ports and address are fine,
      // we need to include a dummy value though
      // because helm does not distinguish between an empty object and unset.
      {
        ...(commonSvAppValues.domain || {}),
        sequencerAddress: decentralizedSynchronizer.namespaceInternalSequencerAddress,
        mediatorAddress: decentralizedSynchronizer.namespaceInternalMediatorAddress,
        // required to prevent participants from using new nodes when the domain is upgraded
        sequencerPublicUrl: `https://sequencer-${decentralizedSynchronizerMigrationConfig.active.id}.${config.ingressName}.${CLUSTER_HOSTNAME}`,
        skipInitialization: svsConfig?.synchronizer?.skipInitialization,
      },
    scan: {
      publicUrl: `https://scan.${config.ingressName}.${CLUSTER_HOSTNAME}`,
      internalUrl: internalScanUrl(config),
    },
    expectedValidatorOnboardings: config.expectedValidatorOnboardings.map(onboarding => ({
      expiresIn: onboarding.expiresIn,
      secretFrom: {
        secretKeyRef: {
          name: validatorOnboardingSecretName(onboarding.name),
          key: 'secret',
          optional: false,
        },
      },
    })),
    isDevNet: config.isDevNet,
    approvedSvIdentities: approvedSvIdentities(),
    persistence: persistenceConfig(postgres, svDbName),
    identitiesExport: config.identitiesBackupLocation,
    participantIdentitiesDumpImport: config.bootstrappingDumpConfig
      ? { secretName: participantBootstrapDumpSecretName }
      : undefined,
    metrics: {
      enable: true,
    },
    additionalJvmOptions: getAdditionalJvmOptions(config.svApp?.additionalJvmOptions),
    failOnAppVersionMismatch: failOnAppVersionMismatch,
    participantAddress: participant.internalClusterAddress,
    onboardingPollingInterval: config.onboardingPollingInterval,
    enablePostgresMetrics: true,
    auth: {
      audience: getSvAppApiAudience(config.auth0Client.getCfg(), xns.logicalName),
      jwksUrl: `https://${config.auth0Client.getCfg().auth0Domain}/.well-known/jwks.json`,
    },
    contactPoint: daContactPoint,
    nodeIdentifier: config.onboardingName,
    delegatelessAutomationExpectedTaskDuration: delegatelessAutomationExpectedTaskDuration,
    delegatelessAutomationExpiredRewardCouponBatchSize:
      delegatelessAutomationExpiredRewardCouponBatchSize,
    maxVettingDelay: networkWideConfig?.maxVettingDelay,
    logLevel: config.logging?.appsLogLevel,
    logAsyncFlush: config.logging?.appsAsync,
    resources: config.svApp?.resources,
    periodicTopologySnapshotConfig: config.periodicTopologySnapshotConfig,
  } as ChartValues;

  if (config.onboarding.type == 'join-with-key') {
    svValues.joinWithKeyOnboarding = {
      sponsorApiUrl: config.onboarding.sponsorApiUrl,
    };
  }

  return installSpliceHelmChart(
    xns,
    'sv-app',
    'splice-sv-node',
    svValues,
    activeVersion,
    {
      dependsOn: dependsOn
        .concat([postgres])
        .concat(participant.asDependencies)
        .concat(decentralizedSynchronizer.dependencies),
    },
    undefined,
    appsAffinityAndTolerations
  );
}

function installScan(
  xns: ExactNamespace,
  config: SvConfig,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  dependsOn: CnInput<Resource>[],
  decentralizedSynchronizerNode: DecentralizedSynchronizerNode,
  svApp: pulumi.Resource,
  participant: SvParticipant,
  postgres: Postgres
) {
  const useCantonBft = decentralizedSynchronizerMigrationConfig.active.sequencer.enableBftSequencer;
  const scanDbName = `scan_${sanitizedForPostgres(config.nodeName)}`;
  const externalSequencerP2pAddress = (
    decentralizedSynchronizerNode as unknown as CantonBftSynchronizerNode
  ).externalSequencerP2pAddress;
  const scanValues = {
    ...spliceInstanceNames,
    metrics: {
      enable: true,
    },
    isFirstSv: config.isFirstSv,
    persistence: persistenceConfig(postgres, scanDbName),
    additionalJvmOptions: getAdditionalJvmOptions(config.scanApp?.additionalJvmOptions),
    failOnAppVersionMismatch: failOnAppVersionMismatch,
    sequencerAddress: decentralizedSynchronizerNode.namespaceInternalSequencerAddress,
    mediatorAddress: decentralizedSynchronizerNode.namespaceInternalMediatorAddress,
    participantAddress: participant.internalClusterAddress,
    migration: {
      id: decentralizedSynchronizerMigrationConfig.active.id,
    },
    ...(useCantonBft
      ? {
          bftSequencers: [
            {
              p2pUrl: externalSequencerP2pAddress,
              migrationId: decentralizedSynchronizerMigrationConfig.active.id,
              sequencerAddress: decentralizedSynchronizerNode.namespaceInternalSequencerAddress,
            },
          ],
        }
      : {}),
    enablePostgresMetrics: true,
    logLevel: config.logging?.appsLogLevel,
    logAsyncFlush: config.logging?.appsAsync,
    additionalEnvVars: config.scanApp?.additionalEnvVars || [],
    resources: config.scanApp?.resources,
  };

  if (svsConfig?.scan?.externalRateLimits) {
    installRateLimits(xns.logicalName, 'scan-app', 5012, svsConfig.scan.externalRateLimits);
  }

  return installSpliceHelmChart(xns, 'scan', 'splice-scan', scanValues, activeVersion, {
    // TODO(#893) if possible, don't require parallel start of sv app and scan when using CantonBft
    dependsOn: dependsOn
      .concat(decentralizedSynchronizerNode.dependencies)
      .concat(
        spliceConfig.pulumiProjectConfig.interAppsDependencies && !useCantonBft
          ? [svApp]
          : participant.asDependencies.concat([postgres])
      ),
  });
}
