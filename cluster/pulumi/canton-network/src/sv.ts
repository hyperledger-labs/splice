import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { Release } from '@pulumi/kubernetes/helm/v3';
import { Resource } from '@pulumi/pulumi';
import {
  Auth0Client,
  auth0UserNameEnvVarSource,
  BackupConfig,
  BootstrappingDumpConfig,
  btoa,
  ChartValues,
  CLUSTER_BASENAME,
  CnInput,
  ExactNamespace,
  exactNamespace,
  ExpectedValidatorOnboarding,
  fetchAndInstallParticipantBootstrapDump,
  getLatestSvcAcsDumpFile,
  installAuth0Secret,
  installAuth0UISecret,
  installBootstrapDataBucketSecret,
  installCNHelmChart,
  installValidatorOnboardingSecret,
  participantBootstrapDumpSecretName,
  PersistenceConfig,
  sanitizedForPostgres,
  SvIdKey,
  validatorOnboardingSecretName,
  ValidatorTopupConfig,
} from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';

import * as postgres from './postgres';
import {
  DomainMigrationIndex,
  GlobalDomainNode,
  GlobalDomainUpgradeConfig,
  installDomainSpecificComponent,
} from './globalDomainNode';
import { installParticipant } from './ledger';
import { installPostgresMetrics, Postgres } from './postgres';
import { StaticCometBftConfigWithNodeName, StaticSvConfig } from './svconfs';
import { installValidatorApp, installValidatorSecrets, ValidatorSecrets } from './validator';

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
  | { type: 'found-collective' }
  | {
      type: 'join-with-key';
      keys: CnInput<SvIdKey>;
      sponsorRelease?: pulumi.Resource;
      sponsorApiUrl: string;
    };

export type ApprovedSvIdentity = { name: string; publicKey: string | pulumi.Output<string> };

export type SequencerPruningConfig = {
  enabled: boolean;
  pruningInterval?: string;
  retentionPeriod?: string;
};

export interface SvConfig extends StaticSvConfig {
  auth0Client: Auth0Client;
  nodeConfigs: {
    founder: StaticCometBftConfigWithNodeName;
    peers: StaticCometBftConfigWithNodeName[];
  };
  onboarding: SvOnboarding;
  approvedSvIdentities: ApprovedSvIdentity[];
  expectedValidatorOnboardings: ExpectedValidatorOnboarding[];
  isDevNet: boolean;
  backupConfig?: BackupConfig;
  bootstrappingDumpConfig?: BootstrappingDumpConfig;
  topupConfig?: ValidatorTopupConfig;
  sequencerPruningConfig: SequencerPruningConfig;
  splitPostgresInstances: boolean;
}

async function getAcsBootstrappingDump(xns: ExactNamespace, config: BootstrappingDumpConfig) {
  const file = await getLatestSvcAcsDumpFile(xns, config);
  return {
    path: file.name,
    bucket: config.bucket,
  };
}

const clusterUrl = `${CLUSTER_BASENAME}.network.canton.global`;

export type InstalledSv = {
  validatorApp: Resource;
  svApp: Release;
  scan: Release;
  globalDomain: GlobalDomainNode;
  participant: Release;
};

export async function installSvNode(
  config: SvConfig,
  globalDomainUpgradeConfig: GlobalDomainUpgradeConfig,
  cometBftSyncSource?: k8s.helm.v3.Release
): Promise<InstalledSv> {
  const xns = exactNamespace(config.nodeName, true);
  const loopback = installCNHelmChart(
    xns,
    'loopback',
    'cn-cluster-loopback-gateway',
    {
      cluster: {
        basename: CLUSTER_BASENAME,
      },
    },
    { dependsOn: [xns.ns] }
  );

  const auth0BackendSecrets: CnInput<pulumi.Resource>[] = [
    await installAuth0Secret(config.auth0Client, xns, 'sv', config.nodeName),
  ];

  const auth0UISecrets: pulumi.Resource[] = [
    await installAuth0UISecret(config.auth0Client, xns, 'sv', config.nodeName),
  ];

  config.backupConfig = config.backupConfig
    ? {
        ...config.backupConfig,
        prefix: config.backupConfig.prefix || `${CLUSTER_BASENAME}/${xns.logicalName}`,
      }
    : undefined;

  const backupConfigSecret: pulumi.Resource | undefined = config.backupConfig
    ? installBootstrapDataBucketSecret(xns, config.backupConfig.bucket)
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
    .concat(backupConfigSecret ? [backupConfigSecret] : [])
    .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : [])
    .concat([loopback]);

  const defaultPostgres = config.splitPostgresInstances
    ? undefined
    : postgres.installPostgres(xns, 'postgres', false);

  const validatorSecrets = await installValidatorSecrets({
    xns,
    auth0Client: config.auth0Client,
    auth0AppName: config.auth0ValidatorAppName,
  });

  const domainSpecificComponents = await installDomainSpecificComponents(
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
    config,
    dependsOn,
    backupConfigSecret,
    validatorSecrets
  );

  installCNHelmChart(
    xns,
    'ingress-sv',
    'cn-cluster-ingress-runbook',
    {
      withSvIngress: true,
      ingress: {
        globalDomain: {
          activeMigrationId: domainSpecificComponents.globalDomain.migrationId.toString(),
        },
      },
      cluster: {
        hostname: `${CLUSTER_BASENAME}.network.canton.global`,
        svNamespace: xns.logicalName,
      },
    },
    { dependsOn: [xns.ns] }
  );

  return domainSpecificComponents;
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
  migrationId: DomainMigrationIndex,
  svConfig: SvConfig,
  participant: Release,
  backupConfigSecret: Resource | undefined,
  svApp: Release,
  scan: Release,
  validatorSecrets: ValidatorSecrets
) {
  const validatorDbName = `validator_${sanitizedForPostgres(svConfig.nodeName)}_${migrationId}`;
  const globalDomainUrl = `https://sequencer-${migrationId}.sv-1.svc.${CLUSTER_BASENAME}.network.canton.global`;

  const validator = await installValidatorApp({
    xns,
    domainMigrationId: migrationId,
    validatorWalletUser: svConfig.validatorWalletUser,
    participant: participant,
    disableAllocateLedgerApiUserParty: true,
    topupConfig: svConfig.topupConfig,
    backupConfig:
      svConfig.backupConfig && backupConfigSecret
        ? {
            config: svConfig.backupConfig,
            secret: backupConfigSecret,
          }
        : undefined,
    persistenceConfig: persistenceConfig(postgres, validatorDbName),
    extraDependsOn: [svApp, postgres, scan],
    svValidator: true,
    participantAddress: participant.name,
    globalDomainUrl: globalDomainUrl,
    scanAddress: pulumi.interpolate`http://scan-app-${migrationId}.${svConfig.nodeName}:5012`,
    secrets: validatorSecrets,
  });
  installPostgresMetrics(postgres, validatorDbName, [validator]);
  return validator;
}

function installDomainSpecificComponents(
  xns: ExactNamespace,
  globalDomainUpgradeConfig: GlobalDomainUpgradeConfig,
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
  svConfig: SvConfig,
  dependsOn: CnInput<pulumi.Resource>[],
  backupConfigSecret: pulumi.Resource | undefined,
  validatorSecrets: ValidatorSecrets
) {
  return installDomainSpecificComponent(
    globalDomainUpgradeConfig,
    async (migrationId, isActive) => {
      const sequencerPostgres =
        defaultPostgres || postgres.installPostgres(xns, `sequencer-${migrationId}-pg`, true);
      const mediatorPostgres =
        defaultPostgres || postgres.installPostgres(xns, `mediator-${migrationId}-pg`, true);
      const participantPostgres =
        defaultPostgres || postgres.installPostgres(xns, `participant-${migrationId}-pg`, true);

      const mustBeManuallyInitialized = !isActive;
      // If we have a dump, we disable auto init.
      const isParticipantRestoringFromDump = !!svConfig.bootstrappingDumpConfig;
      const participant = installParticipant(
        xns,
        `participant-${migrationId}`,
        participantPostgres,
        auth0UserNameEnvVarSource('sv'),
        isParticipantRestoringFromDump || mustBeManuallyInitialized
      );
      const globalDomainNode = new GlobalDomainNode(
        migrationId,
        xns,
        sequencerPostgres,
        mediatorPostgres,
        // legacy domains don't need cometbft state sync because no new nodes will join
        // upgrade domains don't need cometbft state sync because until they are active cometbft will not really progress its height a lot
        isActive ? cometbft : { ...cometbft, syncSource: undefined },
        mustBeManuallyInitialized,
        isActive
      );
      let svAppConfigOverrides = {};
      if (migrationId === globalDomainUpgradeConfig.upgradeMigrationId) {
        svAppConfigOverrides = {
          onboarding: {
            type: 'domain-migration',
          },
        };
      }
      installCNHelmChart(
        xns,
        'ingress-domain-' + migrationId,
        'cn-cluster-ingress-runbook',
        {
          ingress: {
            wallet: false,
            cns: false,
            scan: true,
            sequencer: true,
            sv: true,
            globalDomain: {
              migrationId: migrationId.toString(),
            },
          },
          cluster: {
            hostname: `${CLUSTER_BASENAME}.network.canton.global`,
            svNamespace: xns.logicalName,
          },
        },
        { dependsOn: [xns.ns] }
      );
      const appsPostgres =
        defaultPostgres || postgres.installPostgres(xns, `cn-apps-${migrationId}-pg`, true);
      const svApp = installSvApp(
        migrationId,
        { ...svConfig, ...svAppConfigOverrides },
        xns,
        dependsOn,
        participant,
        globalDomainNode,
        globalDomainUpgradeConfig.prepareUpgrade ||
          globalDomainUpgradeConfig.upgradeMigrationId != undefined,
        appsPostgres,
        svConfig.backupConfig
      );
      const scan = installScan(
        xns,
        migrationId,
        svConfig.nodeName,
        svConfig.onboarding.type,
        globalDomainNode,
        svApp,
        participant,
        appsPostgres
      );
      const validatorApp = await installValidator(
        appsPostgres,
        xns,
        migrationId,
        svConfig,
        participant,
        backupConfigSecret,
        svApp,
        scan,
        validatorSecrets
      );

      return {
        globalDomain: globalDomainNode,
        participant: participant,
        svApp: svApp,
        scan: scan,
        validatorApp: validatorApp,
      };
    }
  );
}

function installSvApp(
  domainMigrationId: DomainMigrationIndex,
  config: SvConfig,
  xns: ExactNamespace,
  dependsOn: CnInput<Resource>[],
  participant: Release,
  globalDomain: GlobalDomainNode,
  mustIncludeUpgradeConfig: boolean,
  postgres: Postgres,
  backupConfig?: BackupConfig
) {
  const svAppName = sanitizedForPostgres(`${config.nodeName}-${domainMigrationId}`);

  const svValues = {
    domainMigrationId: domainMigrationId.toString(),
    onboardingType: config.onboarding.type,
    onboardingName: config.onboardingName,
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
        sequencerPublicUrl: `https://sequencer-${domainMigrationId}.${config.nodeName}.svc.${CLUSTER_BASENAME}.network.canton.global`,
        sequencerPruningConfig: config.sequencerPruningConfig,
      },
    scan: {
      publicUrl: `https://scan-${domainMigrationId}.${config.nodeName}.svc.${clusterUrl}`,
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
    persistence: persistenceConfig(postgres, svAppName),
    acsDumpPeriodicExport: backupConfig,
    acsDumpImport:
      config.bootstrappingDumpConfig && config.onboarding.type === 'found-collective'
        ? getAcsBootstrappingDump(xns, config.bootstrappingDumpConfig)
        : undefined,
    participantIdentitiesDumpImport: config.bootstrappingDumpConfig
      ? { secretName: participantBootstrapDumpSecretName }
      : undefined,
    metrics: {
      enable: true,
    },
    additionalJvmOptions: jmxOptions(),
    participantAddress: participant.name,
  } as ChartValues;

  if (config.onboarding.type == 'join-with-key') {
    svValues.joinWithKeyOnboarding = {
      sponsorApiUrl: config.onboarding.sponsorApiUrl,
    };
  }
  if (mustIncludeUpgradeConfig) {
    svValues.globalDomainUpgrade = {
      isActiveNode: globalDomain.active,
      storageClassName: 'standard-rwo',
    };
  }

  const svApp = installCNHelmChart(xns, `sv-app-${domainMigrationId}`, 'cn-sv-node', svValues, {
    dependsOn: dependsOn.concat([participant, postgres, globalDomain]),
  });

  installPostgresMetrics(postgres, svAppName, [svApp]);

  return svApp;
}

function installScan(
  xns: ExactNamespace,
  domainMigrationId: DomainMigrationIndex,
  nodename: string,
  svConfigOnboardingType: string,
  globalDomainNode: GlobalDomainNode,
  svApp: Release,
  participant: Release,
  postgres: Postgres
) {
  const scanDbName = `scan_${sanitizedForPostgres(nodename)}_${domainMigrationId}`;
  // const scanDb = scanAppPostgres.createDatabase(scanDbName);
  let ingestFromParticipantBegin;
  if (svConfigOnboardingType == 'found-collective') {
    ingestFromParticipantBegin = true;
  } else {
    ingestFromParticipantBegin = false;
  }
  const scanValues = {
    clusterUrl,
    metrics: {
      enable: true,
    },
    persistence: persistenceConfig(postgres, scanDbName),
    additionalJvmOptions: jmxOptions(),
    ingestFromParticipantBegin: ingestFromParticipantBegin,
    sequencerAddress: globalDomainNode.namespaceInternalSequencerAddress,
    participantAddress: participant.name,
    domainMigrationId: domainMigrationId.toString(),
  };
  const scanApp = installCNHelmChart(xns, `scan-${domainMigrationId}`, 'cn-scan', scanValues, {
    dependsOn: [svApp, globalDomainNode],
  });
  installPostgresMetrics(postgres, scanDbName, [scanApp]);
  return scanApp;
}
