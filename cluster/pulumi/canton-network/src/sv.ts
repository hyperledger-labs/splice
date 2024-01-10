import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import type { Auth0Client, CnInput, SvIdKey } from 'cn-pulumi-common';
import {
  auth0UserNameEnvVarSource,
  BackupConfig,
  BootstrappingDumpConfig,
  btoa,
  ChartValues,
  CLUSTER_BASENAME,
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
  validatorOnboardingSecretName,
  ValidatorTopupConfig,
} from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';

import * as postgres from './postgres';
import {
  DomainIndex,
  GlobalDomainUpgradeConfig,
  installDomainSpecificComponent,
  installGlobalDomain,
} from './globalDomainNode';
import { installParticipant } from './ledger';
import { Postgres, initDatabase } from './postgres';
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
  | { type: 'found-collective' }
  | {
      type: 'join-with-key';
      keys: CnInput<SvIdKey>;
      sequencerDatabase: postgres.Postgres;
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

function installParticipants(
  globalUpradeDomainConfig: GlobalDomainUpgradeConfig,
  config: SvConfig,
  xns: ExactNamespace,
  existingPostgres: Postgres
) {
  const participantPostgres = (id: DomainIndex) =>
    config.splitPostgresInstances
      ? postgres.installPostgres(xns, `participant-${id}-pg`, true)
      : existingPostgres;

  return installDomainSpecificComponent(
    globalUpradeDomainConfig,
    defaultId =>
      installParticipant(
        xns,
        `participant-${defaultId}`,
        participantPostgres(defaultId),
        auth0UserNameEnvVarSource('sv'),
        // If we have a dump, we disable auto init.
        !!config.bootstrappingDumpConfig
      ),
    id =>
      installParticipant(
        xns,
        `participant-${id}`,
        participantPostgres(id),
        auth0UserNameEnvVarSource('sv'),
        true
      )
  );
}

export async function installSvNode(
  config: SvConfig,
  globalDomainUpgradeConfig: GlobalDomainUpgradeConfig,
  cometBftSyncSource?: k8s.helm.v3.Release
): Promise<{
  svApp: k8s.helm.v3.Release;
  sequencerPostgres: postgres.Postgres;
}> {
  const xns = exactNamespace(config.nodename);

  const auth0BackendSecrets: CnInput<pulumi.Resource>[] = [
    await installAuth0Secret(config.auth0Client, xns, 'sv', config.nodename),
  ];

  const auth0UISecrets: pulumi.Resource[] = [
    await installAuth0UISecret(config.auth0Client, xns, 'sv', config.nodename),
  ];

  const backupConfig: BackupConfig | undefined = config.backupConfig
    ? {
        ...config.backupConfig,
        prefix: config.backupConfig.prefix
          ? config.backupConfig.prefix
          : `${CLUSTER_BASENAME}/${xns.logicalName}`,
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

  const sequencerPostgres = postgres.installPostgres(
    xns,
    config.splitPostgresInstances ? 'sequencer-pg' : 'postgres',
    config.splitPostgresInstances
  );
  const mediatorPostgres = config.splitPostgresInstances
    ? postgres.installPostgres(xns, 'mediator-pg', true)
    : sequencerPostgres;

  const globalDomain = installGlobalDomain(
    globalDomainUpgradeConfig,
    xns,
    sequencerPostgres,
    mediatorPostgres,
    {
      name: config.nodename,
      onboardingName: config.onboardingName,
      syncSource: cometBftSyncSource,
    }
  );
  const participant = installParticipants(
    globalDomainUpgradeConfig,
    config,
    xns,
    sequencerPostgres
  );

  const svAppPostgres = config.splitPostgresInstances
    ? postgres.installPostgres(xns, 'sv-pg', true)
    : sequencerPostgres;
  const svAppName = config.nodename.replaceAll('-', '_');
  const svDb = svAppPostgres.createDatabaseAndInstallMetrics(svAppName);
  const sequencerAddress = `${globalDomain.name}-sequencer`;
  const participantAddress = participant.name;

  const initDb = initDatabase();

  const clusterUrl = `${CLUSTER_BASENAME}.network.canton.global`;

  const svValues = {
    onboardingType: config.onboarding.type,
    onboardingName: config.onboardingName,
    cometBFT: {
      enabled: true,
      connectionUri: pulumi.interpolate`http://${globalDomain.cometbftRpcService.metadata.name}:26657`,
    },
    globalDomainUrl: `http://${sequencerAddress}.sv-1:5008`,
    domain:
      // defaults for ports and address are fine,
      // we need to include a dummy value though
      // because helm does not distinguish between an empty object and unset.
      {
        sequencerAddress: sequencerAddress,
        mediatorAddress: `${globalDomain.name}-mediator`,
        // required to prevent participants from using new nodes when the domain is upgraded
        sequencerPublicUrl: `https://sequencer-${globalDomain.id}.${config.nodename}.svc.${CLUSTER_BASENAME}.network.canton.global`,
        sequencerPruningConfig: config.sequencerPruningConfig,
      },
    scan: {
      publicUrl: `https://scan.${config.nodename}.svc.${clusterUrl}`,
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
    participantAddress,
    init: initDb && { initDb },
  } as ChartValues;

  if (config.onboarding.type == 'join-with-key') {
    svValues.joinWithKeyOnboarding = {
      sponsorApiUrl: config.onboarding.sponsorApiUrl,
    };
  }

  installCNHelmChart(
    xns,
    'ingress-sv',
    'cn-cluster-ingress-runbook',
    {
      withSvIngress: true,
      ingress: {
        sequencer: {
          activeGlobalDomain: globalDomain.id.toString(),
        },
      },
      cluster: {
        hostname: `${CLUSTER_BASENAME}.network.canton.global`,
        svNamespace: xns.logicalName,
      },
    },
    { dependsOn: [xns.ns] }
  );

  const svApp = installCNHelmChart(xns, 'sv-app', 'cn-sv-node', svValues, {
    dependsOn: dependsOn.concat([participant, svAppPostgres, svDb, globalDomain]),
  });

  const scanAppPostgres = config.splitPostgresInstances
    ? postgres.installPostgres(xns, 'scan-pg', true)
    : sequencerPostgres;
  const scanDbName = `scan_${svAppName}`;
  const scanDb = scanAppPostgres.createDatabaseAndInstallMetrics(scanDbName);
  const scanValues = {
    clusterUrl,
    metrics: {
      enable: true,
    },
    persistence: persistenceConfig(scanAppPostgres, scanDbName),
    additionalJvmOptions: jmxOptions(),
    sequencerAddress: sequencerAddress,
    init: initDb && { initDb },
    participantAddress,
  };
  installCNHelmChart(xns, 'scan', 'cn-scan', scanValues, {
    dependsOn: [svApp, scanAppPostgres, scanDb, globalDomain],
  });

  const validatorPostgres = config.splitPostgresInstances
    ? postgres.installPostgres(xns, 'validator-pg', true)
    : sequencerPostgres;
  const validatorDbName = `validator_${svAppName}`;
  const validatorDb = validatorPostgres.createDatabaseAndInstallMetrics(validatorDbName);

  await installValidatorApp({
    auth0Client: config.auth0Client,
    xns,
    validatorWalletUser: config.validatorWalletUser,
    participant,
    disableAllocateLedgerApiUserParty: true,
    auth0AppName: config.auth0ValidatorAppName,
    topupConfig: config.topupConfig,
    backupConfig:
      backupConfig && backupConfigSecret
        ? {
            config: backupConfig,
            secret: backupConfigSecret,
          }
        : undefined,
    persistenceConfig: persistenceConfig(validatorPostgres, validatorDbName),
    extraDependsOn: [svApp, validatorPostgres, validatorDb],
    svValidator: true,
    participantAddress,
  });

  return { svApp, sequencerPostgres: sequencerPostgres };
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
