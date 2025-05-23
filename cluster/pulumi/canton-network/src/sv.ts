import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as postgres from 'splice-pulumi-common/src/postgres';
import { Resource } from '@pulumi/pulumi';
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
  DecentralizedSynchronizerUpgradeConfig,
  DEFAULT_AUDIENCE,
  ExactNamespace,
  exactNamespace,
  fetchAndInstallParticipantBootstrapDump,
  imagePullSecret,
  initialPackageConfigJson,
  initialSynchronizerFeesConfig,
  installAuth0Secret,
  installAuth0UISecret,
  installBootstrapDataBucketSecret,
  InstalledHelmChart,
  installSpliceHelmChart,
  installValidatorOnboardingSecret,
  participantBootstrapDumpSecretName,
  PersistenceConfig,
  sanitizedForPostgres,
  spliceInstanceNames,
  svCometBftGovernanceKeySecret,
  SvIdKey,
  svUserIds,
  txLogBackfillingValues,
  validatorOnboardingSecretName,
} from 'splice-pulumi-common';
import {
  CantonBftSynchronizerNode,
  CometbftSynchronizerNode,
  DecentralizedSynchronizerNode,
  InstalledMigrationSpecificSv,
  SvParticipant,
} from 'splice-pulumi-common-sv';
import { SvConfig } from 'splice-pulumi-common-sv/src/config';
import {
  installValidatorApp,
  installValidatorSecrets,
} from 'splice-pulumi-common-validator/src/validator';
import { spliceConfig } from 'splice-pulumi-common/src/config/config';
import { initialAmuletPrice } from 'splice-pulumi-common/src/initialAmuletPrice';
import { jmxOptions } from 'splice-pulumi-common/src/jmx';
import { Postgres } from 'splice-pulumi-common/src/postgres';
import { failOnAppVersionMismatch } from 'splice-pulumi-common/src/upgrades';

import { buildCrossStackCantonDependencies } from './canton';

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
  decentralizedSynchronizerUpgradeConfig: DecentralizedSynchronizerMigrationConfig
): Promise<InstalledSv> {
  const xns = exactNamespace(baseConfig.nodeName, true);
  const loopback = installSpliceHelmChart(
    xns,
    'loopback',
    'splice-cluster-loopback-gateway',
    {
      cluster: {
        hostname: CLUSTER_HOSTNAME,
      },
      cometbftPorts: {
        // This ensures the loopback exposes the right ports. We need a +1 since the helm chart does an exclusive range
        domains: DecentralizedSynchronizerUpgradeConfig.highestMigrationId + 1,
      },
    },
    activeVersion,
    { dependsOn: [xns.ns] }
  );
  const imagePullDeps = imagePullSecret(xns);

  const auth0BackendSecrets: CnInput<pulumi.Resource>[] = [
    await installAuth0Secret(baseConfig.auth0Client, xns, 'sv', baseConfig.auth0SvAppName),
  ];

  const auth0UISecrets: pulumi.Resource[] = [
    await installAuth0UISecret(baseConfig.auth0Client, xns, 'sv', baseConfig.nodeName),
  ];

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

  const identitiesBackupLocation = {
    ...baseConfig.identitiesBackupLocation,
    prefix: baseConfig.identitiesBackupLocation.prefix || `${CLUSTER_BASENAME}/${xns.logicalName}`,
  };

  const config = { ...baseConfig, periodicBackupConfig, identitiesBackupLocation };

  const identitiesBackupConfigSecret = installBootstrapDataBucketSecret(
    xns,
    config.identitiesBackupLocation.bucket
  );

  const backupConfigSecret: pulumi.Resource | undefined = config.periodicBackupConfig
    ? config.periodicBackupConfig.location.bucket != config.identitiesBackupLocation.bucket
      ? installBootstrapDataBucketSecret(xns, config.periodicBackupConfig.location.bucket)
      : identitiesBackupConfigSecret
    : undefined;

  const participantBootstrapDumpSecret: pulumi.Resource | undefined = config.bootstrappingDumpConfig
    ? await fetchAndInstallParticipantBootstrapDump(xns, config.bootstrappingDumpConfig)
    : undefined;

  const dependsOn: CnInput<pulumi.Resource>[] = auth0BackendSecrets
    .concat(auth0UISecrets)
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
    .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : [])
    .concat([loopback])
    .concat(imagePullDeps)
    .concat(
      config.cometBftGovernanceKey
        ? svCometBftGovernanceKeySecret(xns, config.cometBftGovernanceKey)
        : []
    );

  const defaultPostgres = config.splitPostgresInstances
    ? undefined
    : postgres.installPostgres(xns, 'postgres', 'postgres', activeVersion, false);

  const appsPostgres =
    defaultPostgres ||
    postgres.installPostgres(xns, `cn-apps-pg`, `cn-apps-pg`, activeVersion, true);
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
    config,
    xns,
    dependsOn,
    appsPostgres,
    canton.participant,
    canton.decentralizedSynchronizer
  );

  const scan = installScan(
    xns,
    config.isFirstSv,
    decentralizedSynchronizerUpgradeConfig,
    config.nodeName,
    canton.decentralizedSynchronizer,
    svApp,
    canton.participant,
    appsPostgres
  );

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
  const validatorSecrets = await installValidatorSecrets({
    xns,
    auth0Client: svConfig.auth0Client,
    auth0AppName: svConfig.auth0ValidatorAppName,
  });

  const validatorDbName = `validator_${sanitizedForPostgres(svConfig.nodeName)}`;
  const decentralizedSynchronizerUrl = `https://sequencer-${decentralizedSynchronizerMigrationConfig.active.id}.sv-2.${CLUSTER_HOSTNAME}`;

  const validator = await installValidatorApp({
    xns,
    migration: {
      id: decentralizedSynchronizerMigrationConfig.active.id,
    },
    validatorWalletUsers: svUserIds(validatorSecrets.auth0Client.getCfg()).apply(ids =>
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
    decentralizedSynchronizerUrl: decentralizedSynchronizerUrl,
    scanAddress: internalScanUrl(svConfig),
    secrets: validatorSecrets,
    sweep: svConfig.sweep,
    nodeIdentifier: svConfig.onboardingName,
  });

  return validator;
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

  const useCantonBft = decentralizedSynchronizerMigrationConfig.active.sequencer.enableBftSequencer;
  const svValues = {
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
    initialAmuletPrice: initialAmuletPrice,
    disableOnboardingParticipantPromotionDelay: config.disableOnboardingParticipantPromotionDelay,
    ...(useCantonBft
      ? {}
      : {
          cometBFT: {
            enabled: true,
            connectionUri: pulumi.interpolate`http://${(decentralizedSynchronizer as unknown as CometbftSynchronizerNode).cometbftRpcServiceName}:26657`,
            externalGovernanceKey: config.cometBftGovernanceKey ? true : undefined,
          },
        }),
    decentralizedSynchronizerUrl:
      config.onboarding.type == 'found-dso'
        ? undefined
        : decentralizedSynchronizer.sv1InternalSequencerAddress,
    domain:
      // defaults for ports and address are fine,
      // we need to include a dummy value though
      // because helm does not distinguish between an empty object and unset.
      {
        sequencerAddress: decentralizedSynchronizer.namespaceInternalSequencerAddress,
        mediatorAddress: decentralizedSynchronizer.namespaceInternalMediatorAddress,
        // required to prevent participants from using new nodes when the domain is upgraded
        sequencerPublicUrl: `https://sequencer-${decentralizedSynchronizerMigrationConfig.active.id}.${config.ingressName}.${CLUSTER_HOSTNAME}`,
        sequencerPruningConfig: config.sequencerPruningConfig,
        ...(useCantonBft
          ? {
              enableBftSequencer: true,
            }
          : {}),
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
    approvedSvIdentities: config.approvedSvIdentities,
    persistence: persistenceConfig(postgres, svDbName),
    identitiesExport: config.identitiesBackupLocation,
    participantIdentitiesDumpImport: config.bootstrappingDumpConfig
      ? { secretName: participantBootstrapDumpSecretName }
      : undefined,
    metrics: {
      enable: true,
    },
    additionalJvmOptions: jmxOptions(),
    failOnAppVersionMismatch: failOnAppVersionMismatch(),
    participantAddress: participant.internalClusterAddress,
    onboardingPollingInterval: config.onboardingPollingInterval,
    enablePostgresMetrics: true,
    auth: {
      audience: config.auth0Client.getCfg().appToApiAudience['sv'] || DEFAULT_AUDIENCE,
      jwksUrl: `https://${config.auth0Client.getCfg().auth0Domain}/.well-known/jwks.json`,
    },
    contactPoint: daContactPoint,
    nodeIdentifier: config.onboardingName,
  } as ChartValues;

  if (config.onboarding.type == 'join-with-key') {
    svValues.joinWithKeyOnboarding = {
      sponsorApiUrl: config.onboarding.sponsorApiUrl,
    };
  }

  const svApp = installSpliceHelmChart(
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
  return svApp;
}

function installScan(
  xns: ExactNamespace,
  isFirstSv: boolean,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  nodename: string,
  decentralizedSynchronizerNode: DecentralizedSynchronizerNode,
  svApp: pulumi.Resource,
  participant: SvParticipant,
  postgres: Postgres
) {
  const useCantonBft = decentralizedSynchronizerMigrationConfig.active.sequencer.enableBftSequencer;
  const scanDbName = `scan_${sanitizedForPostgres(nodename)}`;
  const externalSequencerP2pAddress = (
    decentralizedSynchronizerNode as unknown as CantonBftSynchronizerNode
  ).externalSequencerP2pAddress;
  const scanValues = {
    ...spliceInstanceNames,
    metrics: {
      enable: true,
    },
    isFirstSv: isFirstSv,
    persistence: persistenceConfig(postgres, scanDbName),
    additionalJvmOptions: jmxOptions(),
    failOnAppVersionMismatch: failOnAppVersionMismatch(),
    sequencerAddress: decentralizedSynchronizerNode.namespaceInternalSequencerAddress,
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
    ...txLogBackfillingValues,
  };
  const scan = installSpliceHelmChart(xns, 'scan', 'splice-scan', scanValues, activeVersion, {
    // TODO(#19670) if possible, don't require parallel start of sv app and scan when using CantonBft
    dependsOn:
      spliceConfig.pulumiProjectConfig.interAppsDependencies && !useCantonBft
        ? decentralizedSynchronizerNode.dependencies.concat([svApp])
        : decentralizedSynchronizerNode.dependencies,
  });
  return scan;
}
