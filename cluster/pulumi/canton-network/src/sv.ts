import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import * as semver from 'semver';
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
  CLUSTER_HOSTNAME,
  CnInput,
  defaultVersion,
  disableCantonAutoInit,
  disableCometBftStateSync,
  ExactNamespace,
  exactNamespace,
  ExpectedValidatorOnboarding,
  fetchAndInstallParticipantBootstrapDump,
  DecentralizedSynchronizerMigrationConfig,
  initialSynchronizerFeesConfig,
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
  ApprovedSvIdentity,
  SV_APP_HELM_CHART_TIMEOUT_SEC,
  config,
  daContactPoint,
  spliceInstanceNames,
} from 'cn-pulumi-common';
import { appsAffinityAndTolerations } from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';
import { failOnAppVersionMismatch } from 'cn-pulumi-common/src/upgrades';

import * as postgres from '../../common/src/postgres';
import { Postgres } from '../../common/src/postgres';
import { DecentralizedSynchronizerNode } from './decentralizedSynchronizerNode';
import { installParticipant } from './participant';
import svConfigs, { StaticCometBftConfigWithNodeName, StaticSvConfig } from './svConfigs';
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
      type: 'found-dso';
      sv1SvRewardWeightBps: number;
      roundZeroDuration?: string;
    }
  | {
      type: 'join-with-key';
      keys: CnInput<SvIdKey>;
      sponsorRelease: pulumi.Resource;
      sponsorApiUrl: string;
    };

export type SequencerPruningConfig = {
  enabled: boolean;
  pruningInterval?: string;
  retentionPeriod?: string;
};

export interface SvConfig extends StaticSvConfig {
  isFirstSv: boolean;
  auth0Client: Auth0Client;
  nodeConfigs: {
    sv1: StaticCometBftConfigWithNodeName;
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
  decentralizedSynchronizer: DecentralizedSynchronizerNode;
  participant: Release;
};

export type InstalledSv = {
  validatorApp: Resource;
  svApp: Release;
  scan: Release;
  decentralizedSynchronizer: DecentralizedSynchronizerNode;
  participant: Release;
  ingress: Resource;
};

export async function installSvNode(
  baseConfig: SvConfig,
  decentralizedSynchronizerUpgradeConfig: DecentralizedSynchronizerMigrationConfig,
  cometBftSyncSource?: k8s.helm.v3.Release
): Promise<InstalledSv> {
  const xns = exactNamespace(baseConfig.nodeName, true);
  const loopback = installCNHelmChart(
    xns,
    'loopback',
    'cn-cluster-loopback-gateway',
    {
      cluster: {
        hostname: CLUSTER_HOSTNAME,
      },
    },
    defaultVersion,
    { dependsOn: [xns.ns] }
  );

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
    : postgres.installPostgres(xns, 'postgres', 'postgres', false);

  const appsPostgres =
    defaultPostgres || postgres.installPostgres(xns, `cn-apps-pg`, `cn-apps-pg`, true);
  const activeMigrationComponents = installMigrationIdSpecificComponents(
    xns,
    decentralizedSynchronizerUpgradeConfig,
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
    decentralizedSynchronizerUpgradeConfig,
    config,
    xns,
    dependsOn,
    activeMigrationComponents.participant,
    activeMigrationComponents.decentralizedSynchronizer,
    appsPostgres
  );

  const scan = installScan(
    xns,
    config.isFirstSv,
    decentralizedSynchronizerUpgradeConfig,
    config.nodeName,
    activeMigrationComponents.decentralizedSynchronizer,
    svApp,
    activeMigrationComponents.participant,
    appsPostgres
  );

  const validatorApp = await installValidator(
    appsPostgres,
    xns,
    decentralizedSynchronizerUpgradeConfig,
    baseConfig,
    backupConfigSecret,
    activeMigrationComponents,
    svApp,
    scan
  );

  const ingress = installCNHelmChart(
    xns,
    'ingress-sv',
    'cn-cluster-ingress-runbook',
    {
      withSvIngress: true,
      ingress: {
        decentralizedSynchronizer: {
          migrationIds: decentralizedSynchronizerUpgradeConfig
            .allMigrationInfos()
            .map(x => x.migrationId.toString()),
        },
      },
      cluster: {
        hostname: CLUSTER_HOSTNAME,
        svNamespace: xns.logicalName,
        svIngressName: config.ingressName,
      },
    },
    defaultVersion,
    { dependsOn: [xns.ns] }
  );

  return { ...activeMigrationComponents, validatorApp, svApp, scan, ingress };
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
  const decentralizedSynchronizerUrl = `https://sequencer-${decentralizedSynchronizerMigrationConfig.active.migrationId}.sv-2.${CLUSTER_HOSTNAME}`;

  const validator = await installValidatorApp({
    xns,
    migration: {
      id: decentralizedSynchronizerMigrationConfig.active.migrationId,
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
    decentralizedSynchronizerUrl: decentralizedSynchronizerUrl,
    scanAddress: internalScanUrl(svConfig),
    secrets: validatorSecrets,
    sweep: svConfig.sweep,
  });

  return validator;
}

function installMigrationIdSpecificComponents(
  xns: ExactNamespace,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  defaultPostgres: Postgres | undefined,
  cometbft: {
    name: string;
    onboardingName: string;
    nodeConfigs: {
      self: StaticCometBftConfigWithNodeName;
      sv1: StaticCometBftConfigWithNodeName;
      peers: StaticCometBftConfigWithNodeName[];
    };
    syncSource?: Release;
  },
  svConfig: SvConfig
) {
  const databaseSuffix = decentralizedSynchronizerMigrationConfig.activeDatabaseId
    ? `${decentralizedSynchronizerMigrationConfig.activeDatabaseId}-pg`
    : 'pg';
  const sequencerPostgres =
    defaultPostgres ||
    postgres.installPostgres(
      xns,
      `sequencer-${databaseSuffix}`,
      `sequencer-${decentralizedSynchronizerMigrationConfig.active.migrationId}-pg`,
      true
    );
  const mediatorPostgres =
    defaultPostgres ||
    postgres.installPostgres(
      xns,
      `mediator-${databaseSuffix}`,
      `mediator-${decentralizedSynchronizerMigrationConfig.active.migrationId}-pg`,
      true
    );
  const participantPostgres =
    defaultPostgres ||
    postgres.installPostgres(
      xns,
      `participant-${databaseSuffix}`,
      `participant-${decentralizedSynchronizerMigrationConfig.active.migrationId}-pg`,
      true
    );
  return installMigrationIdSpecificComponent(
    decentralizedSynchronizerMigrationConfig,
    (migrationId, isActive, version) => {
      let participantDb = undefined,
        sequencerDb = undefined,
        mediatorDb = undefined;
      if (
        migrationId === decentralizedSynchronizerMigrationConfig.upgrade?.migrationId &&
        decentralizedSynchronizerMigrationConfig.useNewDatabasesForMigration
      ) {
        participantDb =
          defaultPostgres ||
          postgres.installPostgres(
            xns,
            `participant-${migrationId}-pg`,
            `participant-${decentralizedSynchronizerMigrationConfig.active.migrationId}-pg`,
            true
          );
        sequencerDb =
          defaultPostgres ||
          postgres.installPostgres(
            xns,
            `sequencer-${migrationId}-pg`,
            `sequencer-${decentralizedSynchronizerMigrationConfig.active.migrationId}-pg`,
            true
          );
        mediatorDb =
          defaultPostgres ||
          postgres.installPostgres(
            xns,
            `mediator-${migrationId}-pg`,
            `mediator-${decentralizedSynchronizerMigrationConfig.active.migrationId}-pg`,
            true
          );
      } else {
        participantDb = participantPostgres;
        sequencerDb = sequencerPostgres;
        mediatorDb = mediatorPostgres;
      }
      const logLevel =
        config.envFlag('CN_DEPLOYMENT_NO_SV_DEBUG') ||
        (config.envFlag('CN_DEPLOYMENT_SINGLE_SV_DEBUG') &&
          svConfig.onboardingName !== svConfigs[0].onboardingName)
          ? 'INFO'
          : 'DEBUG';

      const mustBeManuallyInitialized =
        disableCantonAutoInit ||
        !isActive ||
        decentralizedSynchronizerMigrationConfig.isRunningMigration();
      // legacy domains don't need cometbft state sync because no new nodes will join
      // upgrade domains don't need cometbft state sync because until they are active cometbft will not really progress its height a lot
      // also for upgrade domains we first deploy the domain and then redeploy the sv app, and as we proxy the calls for state sync through the
      // sv-app we cannot configure state sync until the sv app has migrated
      // if a migration is running we must not configure state sync because that will also add a pulumi dependency and our migrate flow will break (sv2-4 depending on sv1)
      const canSyncFromCometBft =
        !disableCometBftStateSync &&
        isActive &&
        !decentralizedSynchronizerMigrationConfig.isRunningMigration();
      // If we have a dump, we disable auto init.
      const isParticipantRestoringFromDump = !!svConfig.bootstrappingDumpConfig;
      const participant = installParticipant(
        xns,
        `participant-${migrationId}`,
        participantDb,
        auth0UserNameEnvVarSource('sv'),
        isParticipantRestoringFromDump || mustBeManuallyInitialized,
        svConfig.onboardingName,
        version,
        svConfig.auth0Client.getCfg(),
        migrationId,
        isActive,
        logLevel
      );
      const decentralizedSynchronizerNode = new DecentralizedSynchronizerNode(
        migrationId,
        xns,
        sequencerDb,
        mediatorDb,
        canSyncFromCometBft ? cometbft : { ...cometbft, syncSource: undefined },
        mustBeManuallyInitialized,
        isActive,
        svConfig.onboardingName,
        logLevel,
        version
      );
      return {
        decentralizedSynchronizer: decentralizedSynchronizerNode,
        participant: participant,
      };
    }
  );
}

function internalScanUrl(config: SvConfig): pulumi.Output<string> {
  return pulumi.interpolate`http://scan-app.${config.nodeName}:5012`;
}

// TODO(#13413) Drop this once the base version of ciperiodic is >= 0.1.16
function onboardingType(onboarding: SvOnboarding): string {
  const supportsRenamedOnboardingType =
    defaultVersion.type == 'local' ||
    defaultVersion.version.startsWith('0.1.16') ||
    semver.gt(defaultVersion.version, '0.1.16');

  if (onboarding.type == 'found-dso' && !supportsRenamedOnboardingType) {
    return 'found-collective';
  }
  return onboarding.type;
}

function installSvApp(
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  config: SvConfig,
  xns: ExactNamespace,
  dependsOn: CnInput<Resource>[],
  participant: Release,
  decentralizedSynchronizer: DecentralizedSynchronizerNode,
  postgres: Postgres
) {
  const svDbName = `sv_${sanitizedForPostgres(config.nodeName)}`;

  const svValues = {
    ...decentralizedSynchronizerMigrationConfig.migratingNodeConfig(),
    ...spliceInstanceNames,
    onboardingType: onboardingType(config.onboarding),
    onboardingName: config.onboardingName,
    onboardingFoundingSvRewardWeightBps:
      config.onboarding.type == 'found-dso' ? config.onboarding.sv1SvRewardWeightBps : undefined,
    onboardingRoundZeroDuration:
      config.onboarding.type == 'found-dso' ? config.onboarding.roundZeroDuration : undefined,
    initialSynchronizerFeesConfig:
      config.onboarding.type == 'found-dso' ? initialSynchronizerFeesConfig : undefined,
    disableOnboardingParticipantPromotionDelay: config.disableOnboardingParticipantPromotionDelay,
    cometBFT: {
      enabled: true,
      connectionUri: pulumi.interpolate`http://${decentralizedSynchronizer.cometbftRpcServiceName}:26657`,
    },
    decentralizedSynchronizerUrl: decentralizedSynchronizer.sv1InternalSequencerAddress,
    domain:
      // defaults for ports and address are fine,
      // we need to include a dummy value though
      // because helm does not distinguish between an empty object and unset.
      {
        sequencerAddress: decentralizedSynchronizer.namespaceInternalSequencerAddress,
        mediatorAddress: decentralizedSynchronizer.namespaceInternalMediatorAddress,
        // required to prevent participants from using new nodes when the domain is upgraded
        sequencerPublicUrl: `https://sequencer-${decentralizedSynchronizerMigrationConfig.active.migrationId}.${config.ingressName}.${CLUSTER_HOSTNAME}`,
        sequencerPruningConfig: config.sequencerPruningConfig,
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
    participantAddress: participant.name,
    onboardingPollingInterval: config.onboardingPollingInterval,
    enablePostgresMetrics: true,
    auth: {
      audience: config.auth0Client.getCfg().appToApiAudience['sv'],
      jwksUrl: `https://${config.auth0Client.getCfg().auth0Domain}/.well-known/jwks.json`,
    },
    contactPoint: daContactPoint,
  } as ChartValues;

  if (config.onboarding.type == 'join-with-key') {
    svValues.joinWithKeyOnboarding = {
      sponsorApiUrl: config.onboarding.sponsorApiUrl,
    };
  }

  const svApp = installCNHelmChart(
    xns,
    `sv-app`,
    'cn-sv-node',
    svValues,
    defaultVersion,
    {
      dependsOn: dependsOn.concat([participant, postgres, decentralizedSynchronizer]),
    },
    undefined,
    appsAffinityAndTolerations,
    SV_APP_HELM_CHART_TIMEOUT_SEC
  );
  return svApp;
}

function installScan(
  xns: ExactNamespace,
  isFirstSv: boolean,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  nodename: string,
  decentralizedSynchronizerNode: DecentralizedSynchronizerNode,
  svApp: Release,
  participant: Release,
  postgres: Postgres
) {
  const scanDbName = `scan_${sanitizedForPostgres(nodename)}`;
  // const scanDb = scanAppPostgres.createDatabase(scanDbName);
  const scanValues = {
    clusterUrl: CLUSTER_HOSTNAME,
    ...spliceInstanceNames,
    metrics: {
      enable: true,
    },
    [supportsRenamedFounder ? 'isFirstSv' : 'isFounder']: isFirstSv,
    persistence: persistenceConfig(postgres, scanDbName),
    additionalJvmOptions: jmxOptions(),
    failOnAppVersionMismatch: failOnAppVersionMismatch(),
    sequencerAddress: decentralizedSynchronizerNode.namespaceInternalSequencerAddress,
    participantAddress: participant.name,
    migration: {
      id: decentralizedSynchronizerMigrationConfig.active.migrationId,
    },
    enablePostgresMetrics: true,
  };
  const scan = installCNHelmChart(xns, `scan`, 'cn-scan', scanValues, defaultVersion, {
    dependsOn: [svApp, decentralizedSynchronizerNode],
  });
  return scan;
}

// TODO (#13845) remove when ciperiodic version >= 0.1.18
const supportsRenamedFounder =
  defaultVersion.type == 'local' ||
  defaultVersion.version.startsWith('0.1.18') ||
  semver.gt(defaultVersion.version, '0.1.18');
