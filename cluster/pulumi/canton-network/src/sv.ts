import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { Resource } from '@pulumi/pulumi';
import {
  Auth0Client,
  auth0UserNameEnvVarSource,
  BackupConfig,
  BackupLocation,
  BootstrappingDumpConfig,
  btoa,
  ChartValues,
  CLUSTER_BASENAME,
  CnInput,
  defaultVersion,
  disableCantonAutoInit,
  disableCometBftStateSync,
  ExactNamespace,
  exactNamespace,
  ExpectedValidatorOnboarding,
  fetchAndInstallParticipantBootstrapDump,
  GlobalDomainMigrationConfig,
  installAuth0Secret,
  installAuth0UISecret,
  installBootstrapDataBucketSecret,
  installCNHelmChart,
  installMigrationIdSpecificComponent,
  installValidatorOnboardingSecret,
  participantBootstrapDumpSecretName,
  PersistenceConfig,
  sanitizedForPostgres,
  SvIdKey,
  validatorOnboardingSecretName,
  ValidatorTopupConfig,
} from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';
import { failOnAppVersionMismatch } from 'cn-pulumi-common/src/upgrades';

import * as postgres from './postgres';
import { GlobalDomainNode } from './globalDomainNode';
import { installParticipant } from './participant';
import { installPostgresMetrics, Postgres } from './postgres';
import { StaticCometBftConfigWithNodeName, StaticSvConfig } from './svConfigs';
import { installValidatorApp, installValidatorSecrets } from './validator';

export function installSvKeySecret(
  xns: ExactNamespace,
  keys: CnInput<SvIdKey>
): k8s.core.v1.Secret {
  const secretName = 'cn-app-sv-key';

  const data = pulumi.output(keys).apply(ks => {
    return {
      public: btoa(ks.publicKey),
      private: btoa(ks.privateKey),
    };
  });

  return new k8s.core.v1.Secret(
    `cn-app-${xns.logicalName}-key`,
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
  );
}

export type SvOnboarding =
  | { type: 'domain-migration' }
  | {
      type: 'found-collective';
      foundingSvRewardWeightBps: number;
      roundZeroDuration?: string;
      initialTickDurationS: number;
      initialHoldingFee: number;
    }
  | {
      type: 'join-with-key';
      keys: CnInput<SvIdKey>;
      sponsorRelease: pulumi.Resource;
      sponsorApiUrl: string;
    };

export type ApprovedSvIdentity = {
  name: string;
  publicKey: string | pulumi.Output<string>;
  rewardWeightBps: number;
};

export type SequencerPruningConfig = {
  enabled: boolean;
  pruningInterval?: string;
  retentionPeriod?: string;
};

export interface SvConfig extends StaticSvConfig {
  isFounder: boolean;
  auth0Client: Auth0Client;
  nodeConfigs: {
    founder: StaticCometBftConfigWithNodeName;
    peers: StaticCometBftConfigWithNodeName[];
  };
  onboarding: SvOnboarding;
  approvedSvIdentities: ApprovedSvIdentity[];
  expectedValidatorOnboardings: ExpectedValidatorOnboarding[];
  isDevNet: boolean;
  periodicBackupConfig?: BackupConfig;
  identitiesBackupLocation: BackupLocation;
  bootstrappingDumpConfig?: BootstrappingDumpConfig;
  topupConfig?: ValidatorTopupConfig;
  sequencerPruningConfig: SequencerPruningConfig;
  splitPostgresInstances: boolean;
  disableOnboardingParticipantPromotionDelay: boolean;
  onboardingPollingInterval?: string;
}

type InstalledMigrationSpecificSv = {
  ingress: Release;
  globalDomain: GlobalDomainNode;
  participant: Release;
};

const clusterUrl = `${CLUSTER_BASENAME}.network.canton.global`;

export type InstalledSv = {
  validatorApp: Resource;
  svApp: Release;
  scan: Release;
  globalDomain: GlobalDomainNode;
  participant: Release;
  ingress: Resource;
};

export async function installSvNode(
  baseConfig: SvConfig,
  globalDomainUpgradeConfig: GlobalDomainMigrationConfig,
  cometBftSyncSource?: k8s.helm.v3.Release
): Promise<InstalledSv> {
  const xns = exactNamespace(baseConfig.nodeName, true);
  const loopback = installCNHelmChart(
    xns,
    'loopback',
    'cn-cluster-loopback-gateway',
    {
      cluster: {
        basename: CLUSTER_BASENAME,
      },
    },
    defaultVersion,
    { dependsOn: [xns.ns] }
  );

  const auth0BackendSecrets: CnInput<pulumi.Resource>[] = [
    await installAuth0Secret(baseConfig.auth0Client, xns, 'sv', baseConfig.nodeName),
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
        ? [installSvKeySecret(xns, config.onboarding.keys)]
        : []
    )
    .concat(
      config.onboarding.type == 'join-with-key' && config.onboarding.sponsorRelease
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
    .concat([loopback]);

  const defaultPostgres = config.splitPostgresInstances
    ? undefined
    : postgres.installPostgres(xns, 'postgres', false);

  const appsPostgres = defaultPostgres || postgres.installPostgres(xns, `cn-apps-pg`, true);
  const activeMigrationComponents = installMigrationIdSpecificComponents(
    xns,
    globalDomainUpgradeConfig,
    defaultPostgres,
    {
      name: config.nodeName,
      onboardingName: config.onboardingName,
      nodeConfigs: {
        ...config.nodeConfigs,
        self: { ...config.cometBft, nodeName: config.nodeName },
      },
      syncSource: cometBftSyncSource,
    },
    config
  ).activeComponent;

  const svApp = installSvApp(
    globalDomainUpgradeConfig,
    config,
    xns,
    dependsOn,
    activeMigrationComponents.participant,
    activeMigrationComponents.globalDomain,
    appsPostgres
  );

  const scan = installScan(
    xns,
    config.isFounder,
    globalDomainUpgradeConfig,
    config.nodeName,
    config.onboarding.type,
    activeMigrationComponents.globalDomain,
    svApp,
    activeMigrationComponents.participant,
    appsPostgres
  );

  const validatorApp = await installValidator(
    appsPostgres,
    xns,
    globalDomainUpgradeConfig,
    baseConfig,
    backupConfigSecret,
    activeMigrationComponents,
    svApp,
    scan
  );

  const activeIngress = installCNHelmChart(
    xns,
    'ingress-sv',
    'cn-cluster-ingress-runbook',
    {
      withSvIngress: true,
      ingress: {
        globalDomain: {
          activeMigrationId: globalDomainUpgradeConfig.active.migrationId.toString(),
        },
      },
      cluster: {
        hostname: `${CLUSTER_BASENAME}.network.canton.global`,
        svNamespace: xns.logicalName,
      },
    },
    defaultVersion,
    { dependsOn: [xns.ns] }
  );

  return { ...activeMigrationComponents, validatorApp, svApp, scan, ingress: activeIngress };
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
  };
}

async function installValidator(
  postgres: Postgres,
  xns: ExactNamespace,
  globalDomainMigrationConfig: GlobalDomainMigrationConfig,
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
  const globalDomainUrl = `https://sequencer-${globalDomainMigrationConfig.active.migrationId}.sv-1.svc.${CLUSTER_BASENAME}.network.canton.global`;

  const validator = await installValidatorApp({
    xns,
    migration: {
      id: globalDomainMigrationConfig.active.migrationId,
    },
    validatorWalletUser: svConfig.validatorWalletUser,
    participant: sv.participant,
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
    extraDependsOn: [svApp, postgres, scan],
    svValidator: true,
    participantAddress: sv.participant.name,
    globalDomainUrl: globalDomainUrl,
    scanAddress: internalScanUrl(svConfig),
    secrets: validatorSecrets,
  });

  installPostgresMetrics(postgres, validatorDbName, [validator]);

  return validator;
}

function installMigrationIdSpecificComponents(
  xns: ExactNamespace,
  globalDomainMigrationConfig: GlobalDomainMigrationConfig,
  defaultPostgres: Postgres | undefined,
  cometbft: {
    name: string;
    onboardingName: string;
    nodeConfigs: {
      self: StaticCometBftConfigWithNodeName;
      founder: StaticCometBftConfigWithNodeName;
      peers: StaticCometBftConfigWithNodeName[];
    };
    syncSource?: Release;
  },
  svConfig: SvConfig
) {
  return installMigrationIdSpecificComponent(
    globalDomainMigrationConfig,
    (migrationId, isActive, version) => {
      const sequencerPostgres =
        defaultPostgres || postgres.installPostgres(xns, `sequencer-${migrationId}-pg`, true);
      const mediatorPostgres =
        defaultPostgres || postgres.installPostgres(xns, `mediator-${migrationId}-pg`, true);
      const participantPostgres =
        defaultPostgres || postgres.installPostgres(xns, `participant-${migrationId}-pg`, true);

      const mustBeManuallyInitialized =
        disableCantonAutoInit || !isActive || globalDomainMigrationConfig.isRunningMigration();
      // legacy domains don't need cometbft state sync because no new nodes will join
      // upgrade domains don't need cometbft state sync because until they are active cometbft will not really progress its height a lot
      // also for upgrade domains we first deploy the domain and then redeploy the sv app, and as we proxy the calls for state sync through the
      // sv-app we cannot configure state sync until the sv app has migrated
      // if a migration is running we must not configure state sync because that will also add a pulumi dependency and our migrate flow will break (sv2-4 depending on sv1)
      const canSyncFromCometBft =
        !disableCometBftStateSync && isActive && !globalDomainMigrationConfig.isRunningMigration();
      // If we have a dump, we disable auto init.
      const isParticipantRestoringFromDump = !!svConfig.bootstrappingDumpConfig;
      const participant = installParticipant(
        xns,
        `participant-${migrationId}`,
        participantPostgres,
        auth0UserNameEnvVarSource('sv'),
        isParticipantRestoringFromDump || mustBeManuallyInitialized,
        svConfig.onboardingName,
        version
      );
      const globalDomainNode = new GlobalDomainNode(
        migrationId,
        xns,
        sequencerPostgres,
        mediatorPostgres,
        canSyncFromCometBft ? cometbft : { ...cometbft, syncSource: undefined },
        mustBeManuallyInitialized,
        isActive,
        svConfig.onboardingName,
        version
      );
      const migrationIngress = installCNHelmChart(
        xns,
        'ingress-domain-' + migrationId,
        'cn-cluster-ingress-runbook',
        {
          ingress: {
            wallet: false,
            cns: false,
            scan: false,
            sequencer: true,
            sv: false,
            globalDomain: {
              migrationId: migrationId.toString(),
            },
          },
          cluster: {
            hostname: `${CLUSTER_BASENAME}.network.canton.global`,
            svNamespace: xns.logicalName,
          },
        },
        version,
        { dependsOn: [xns.ns] }
      );
      return {
        globalDomain: globalDomainNode,
        participant: participant,
        ingress: migrationIngress,
      };
    }
  );
}

function internalScanUrl(config: SvConfig): pulumi.Output<string> {
  return pulumi.interpolate`http://scan-app.${config.nodeName}:5012`;
}

function installSvApp(
  globalDomainMigrationConfig: GlobalDomainMigrationConfig,
  config: SvConfig,
  xns: ExactNamespace,
  dependsOn: CnInput<Resource>[],
  participant: Release,
  globalDomain: GlobalDomainNode,
  postgres: Postgres
) {
  const svDbName = `sv_${sanitizedForPostgres(config.nodeName)}`;

  const svValues = {
    ...globalDomainMigrationConfig.migratingNodeConfig(),
    onboardingType: config.onboarding.type,
    onboardingName: config.onboardingName,
    onboardingFoundingSvRewardWeightBps:
      config.onboarding.type == 'found-collective'
        ? config.onboarding.foundingSvRewardWeightBps
        : undefined,
    onboardingRoundZeroDuration:
      config.onboarding.type == 'found-collective'
        ? config.onboarding.roundZeroDuration
        : undefined,
    initialTickDuration:
      config.onboarding.type == 'found-collective'
        ? `${config.onboarding.initialTickDurationS} seconds`
        : undefined,
    initialHoldingFee:
      config.onboarding.type == 'found-collective'
        ? config.onboarding.initialHoldingFee
        : undefined,
    disableOnboardingParticipantPromotionDelay: config.disableOnboardingParticipantPromotionDelay,
    cometBFT: {
      enabled: true,
      connectionUri: pulumi.interpolate`http://${globalDomain.cometbftRpcService.metadata.name}:26657`,
    },
    globalDomainUrl: globalDomain.founderInternalSequencerAddress,
    domain:
      // defaults for ports and address are fine,
      // we need to include a dummy value though
      // because helm does not distinguish between an empty object and unset.
      {
        sequencerAddress: globalDomain.namespaceInternalSequencerAddress,
        mediatorAddress: globalDomain.namespaceInternalMediatorAddress,
        // required to prevent participants from using new nodes when the domain is upgraded
        sequencerPublicUrl: `https://sequencer-${globalDomainMigrationConfig.active.migrationId}.${config.nodeName}.svc.${CLUSTER_BASENAME}.network.canton.global`,
        sequencerPruningConfig: config.sequencerPruningConfig,
      },
    scan: {
      publicUrl: `https://scan.${config.nodeName}.svc.${clusterUrl}`,
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
    participantAddress: participant.name,
    onboardingPollingInterval: config.onboardingPollingInterval,
  } as ChartValues;

  if (config.onboarding.type == 'join-with-key') {
    svValues.joinWithKeyOnboarding = {
      sponsorApiUrl: config.onboarding.sponsorApiUrl,
    };
  }

  const svApp = installCNHelmChart(xns, `sv-app`, 'cn-sv-node', svValues, defaultVersion, {
    dependsOn: dependsOn.concat([participant, postgres, globalDomain]),
  });
  installPostgresMetrics(postgres, svDbName, [svApp]);
  return svApp;
}

function installScan(
  xns: ExactNamespace,
  isFounder: boolean,
  globalDomainMigrationConfig: GlobalDomainMigrationConfig,
  nodename: string,
  svConfigOnboardingType: string,
  globalDomainNode: GlobalDomainNode,
  svApp: Release,
  participant: Release,
  postgres: Postgres
) {
  const scanDbName = `scan_${sanitizedForPostgres(nodename)}`;
  // const scanDb = scanAppPostgres.createDatabase(scanDbName);
  const scanValues = {
    clusterUrl,
    metrics: {
      enable: true,
    },
    isFounder: isFounder,
    persistence: persistenceConfig(postgres, scanDbName),
    additionalJvmOptions: jmxOptions(),
    failOnAppVersionMismatch: failOnAppVersionMismatch(),
    sequencerAddress: globalDomainNode.namespaceInternalSequencerAddress,
    participantAddress: participant.name,
    migration: {
      id: globalDomainMigrationConfig.active.migrationId,
    },
  };
  const scan = installCNHelmChart(xns, `scan`, 'cn-scan', scanValues, defaultVersion, {
    dependsOn: [svApp, globalDomainNode],
  });
  installPostgresMetrics(postgres, scanDbName, [scan]);
  return scan;
}
