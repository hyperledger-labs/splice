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
  DomainIndex,
  GlobalDomainNode,
  GlobalDomainUpgradeConfig,
  installDomainSpecificComponent,
} from './globalDomainNode';
import { installParticipant } from './ledger';
import { enableCloudSql, Postgres } from './postgres';
import { installValidatorApp } from './validator';

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

export type ApprovedSvIdentity = { name: string; publicKey: string };

export type SequencerPruningConfig = {
  enabled: boolean;
  pruningInterval?: string;
  retentionPeriod?: string;
};

export type SvConfig = {
  auth0Client: Auth0Client;
  nodename: string;
  onboardingName: string;
  validatorWalletUser: string;
  onboarding: SvOnboarding;
  approvedSvIdentities: ApprovedSvIdentity[];
  expectedValidatorOnboardings: ExpectedValidatorOnboarding[];
  isDevNet: boolean;
  backupConfig?: BackupConfig;
  bootstrappingDumpConfig?: BootstrappingDumpConfig;
  topupConfig?: ValidatorTopupConfig;
  sequencerPruningConfig: SequencerPruningConfig;
  auth0ValidatorAppName: string;
  splitPostgresInstances: boolean;
};

async function getAcsBootstrappingDump(xns: ExactNamespace, config: BootstrappingDumpConfig) {
  const file = await getLatestSvcAcsDumpFile(xns, config);
  return {
    path: file.name,
    bucket: config.bucket,
  };
}

const clusterUrl = `${CLUSTER_BASENAME}.network.canton.global`;

export async function installSvNode(
  config: SvConfig,
  globalDomainUpgradeConfig: GlobalDomainUpgradeConfig,
  cometBftSyncSource?: k8s.helm.v3.Release
): Promise<{
  svApp: k8s.helm.v3.Release;
}> {
  const xns = exactNamespace(config.nodename);

  const auth0BackendSecrets: CnInput<pulumi.Resource>[] = [
    await installAuth0Secret(config.auth0Client, xns, 'sv', config.nodename),
  ];

  const auth0UISecrets: pulumi.Resource[] = [
    await installAuth0UISecret(config.auth0Client, xns, 'sv', config.nodename),
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
    .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : []);

  const defaultPostgres = config.splitPostgresInstances
    ? undefined
    : postgres.installPostgres(xns, 'postgres', false);

  const domainSpecificComponents = installDomainSpecificComponents(
    xns,
    globalDomainUpgradeConfig,
    defaultPostgres,
    {
      name: config.nodename,
      onboardingName: config.onboardingName,
      syncSource: cometBftSyncSource,
    },
    config,
    dependsOn
  );

  const participantAddress = domainSpecificComponents.participant.name;

  installCNHelmChart(
    xns,
    'ingress-sv',
    'cn-cluster-ingress-runbook',
    {
      withSvIngress: true,
      ingress: {
        globalDomain: {
          activeGlobalDomainId: domainSpecificComponents.globalDomain.id.toString(),
        },
      },
      cluster: {
        hostname: `${CLUSTER_BASENAME}.network.canton.global`,
        svNamespace: xns.logicalName,
      },
    },
    [],
    { dependsOn: [xns.ns] }
  );

  const validatorPostgres = defaultPostgres || postgres.installPostgres(xns, 'validator-pg', true);
  const validatorDbName = `validator_${sanitizedForPostgres(config.nodename)}`;
  const validatorDb = validatorPostgres.createDatabase(validatorDbName);
  const globalDomainUrl = `https://sequencer.sv-1.svc.${CLUSTER_BASENAME}.network.canton.global`;

  await installValidatorApp({
    auth0Client: config.auth0Client,
    xns,
    validatorWalletUser: config.validatorWalletUser,
    participant: domainSpecificComponents.participant,
    disableAllocateLedgerApiUserParty: true,
    auth0AppName: config.auth0ValidatorAppName,
    topupConfig: config.topupConfig,
    backupConfig:
      config.backupConfig && backupConfigSecret
        ? {
            config: config.backupConfig,
            secret: backupConfigSecret,
          }
        : undefined,
    persistenceConfig: persistenceConfig(validatorPostgres, validatorDbName),
    extraDependsOn: [
      domainSpecificComponents.svApp,
      validatorPostgres,
      domainSpecificComponents.scan,
    ],
    svValidator: true,
    participantAddress,
    validatorDb,
    globalDomainUrl: globalDomainUrl,
    scanAddress: pulumi.interpolate`http://scan-app-${domainSpecificComponents.globalDomain.id}.sv-1:5012`,
  });

  return { svApp: domainSpecificComponents.svApp };
}

function persistenceConfig(postgresDb: postgres.Postgres, dbName: string): PersistenceConfig {
  const dbNameO = pulumi.Output.create(dbName);
  return {
    host: postgresDb.address,
    createDb: !enableCloudSql,
    databaseName: dbNameO,
    secretName: postgresDb.secretName,
    schema: dbNameO,
    user: pulumi.Output.create('cnadmin'),
    port: pulumi.Output.create(5432),
  };
}

function installDomainSpecificComponents(
  xns: ExactNamespace,
  globalDomainUpgradeConfig: GlobalDomainUpgradeConfig,
  defaultPostgres: Postgres | undefined,
  cometbft: {
    name: string;
    onboardingName: string;
    syncSource?: Release;
  },
  svConfig: SvConfig,
  dependsOn: CnInput<pulumi.Resource>[]
) {
  return installDomainSpecificComponent(globalDomainUpgradeConfig, (id, isActive) => {
    const sequencerPostgres =
      defaultPostgres || postgres.installPostgres(xns, `sequencer-${id}-pg`, true);
    const mediatorPostgres =
      defaultPostgres || postgres.installPostgres(xns, `mediator-${id}-pg`, true);
    const participantPostgres =
      defaultPostgres || postgres.installPostgres(xns, `participant-${id}-pg`, true);

    const mustBeManuallyInitialized = !isActive;
    // If we have a dump, we disable auto init.
    const isParticipantRestoringFromDump = !!svConfig.bootstrappingDumpConfig;
    const participant = installParticipant(
      xns,
      `participant-${id}`,
      participantPostgres,
      auth0UserNameEnvVarSource('sv'),
      isParticipantRestoringFromDump || mustBeManuallyInitialized
    );
    const globalDomainNode = new GlobalDomainNode(
      id,
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
    if (id === globalDomainUpgradeConfig.upgradeGlobalDomainId) {
      svAppConfigOverrides = {
        onboarding: {
          type: 'domain-migration',
        },
      };
    }
    installCNHelmChart(
      xns,
      'ingress-domain-' + id,
      'cn-cluster-ingress-runbook',
      {
        ingress: {
          wallet: false,
          cns: false,
          scan: true,
          sequencer: true,
          sv: true,
          globalDomain: {
            globalDomainId: id.toString(),
          },
        },
        cluster: {
          hostname: `${CLUSTER_BASENAME}.network.canton.global`,
          svNamespace: xns.logicalName,
        },
      },
      [],
      { dependsOn: [xns.ns] }
    );
    const svApp = installSvApp(
      id,
      { ...svConfig, ...svAppConfigOverrides },
      xns,
      dependsOn,
      participant,
      globalDomainNode,
      globalDomainUpgradeConfig.prepareUpgrade ||
        globalDomainUpgradeConfig.upgradeGlobalDomainId != undefined,
      svConfig.backupConfig
    );
    const scan = installScan(
      xns,
      id,
      svConfig.nodename,
      globalDomainNode,
      svApp,
      participant,
      defaultPostgres
    );

    return {
      globalDomain: globalDomainNode,
      participant: participant,
      svApp: svApp,
      scan: scan,
    };
  });
}

function installSvApp(
  domainId: DomainIndex,
  config: SvConfig,
  xns: ExactNamespace,
  dependsOn: CnInput<Resource>[],
  participant: Release,
  globalDomain: GlobalDomainNode,
  mustIncludeUpgradeConfig: boolean,
  backupConfig?: BackupConfig,
  defaultPostgres?: Postgres
) {
  const svAppPostgres =
    defaultPostgres || postgres.installPostgres(xns, `sv-app-${domainId}-pg`, true);
  const svAppName = sanitizedForPostgres(`${config.nodename}-${domainId}`);
  const svDb = svAppPostgres.createDatabase(svAppName);

  const svValues = {
    domainId: domainId.toString(),
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
        sequencerPublicUrl: `https://sequencer-${domainId}.${config.nodename}.svc.${CLUSTER_BASENAME}.network.canton.global`,
        sequencerPruningConfig: config.sequencerPruningConfig,
      },
    scan: {
      publicUrl: `https://scan-${domainId}.${config.nodename}.svc.${clusterUrl}`,
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
    persistence: persistenceConfig(svAppPostgres, svAppName),
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

  return installCNHelmChart(xns, `sv-app-${domainId}`, 'cn-sv-node', svValues, [svDb], {
    dependsOn: dependsOn.concat([participant, svAppPostgres, globalDomain]),
  });
}

function installScan(
  xns: ExactNamespace,
  domainId: DomainIndex,
  nodename: string,
  globalDomainNode: GlobalDomainNode,
  svApp: Release,
  participant: Release,
  defaultPostgres?: Postgres
) {
  const scanAppPostgres =
    defaultPostgres || postgres.installPostgres(xns, `scan-${domainId}-pg`, true);
  const scanDbName = `scan_${sanitizedForPostgres(nodename)}_${domainId}`;
  const scanDb = scanAppPostgres.createDatabase(scanDbName);
  const scanValues = {
    clusterUrl,
    metrics: {
      enable: true,
    },
    persistence: persistenceConfig(scanAppPostgres, scanDbName),
    additionalJvmOptions: jmxOptions(),
    sequencerAddress: globalDomainNode.namespaceInternalSequencerAddress,
    participantAddress: participant.name,
    domainId: domainId.toString(),
  };
  return installCNHelmChart(xns, `scan-${domainId}`, 'cn-scan', scanValues, [scanDb], {
    dependsOn: [svApp, globalDomainNode],
  });
}
