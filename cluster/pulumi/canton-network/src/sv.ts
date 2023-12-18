import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  auth0UserNameEnvVarSource,
  btoa,
  BackupConfig,
  BootstrappingDumpConfig,
  ValidatorTopupConfig,
  ChartValues,
  CLUSTER_BASENAME,
  ExactNamespace,
  exactNamespace,
  fetchAndInstallParticipantBootstrapDump,
  getLatestSvcAcsDumpFile,
  installAuth0Secret,
  installAuth0UISecret,
  installCNHelmChart,
  installBootstrapDataBucketSecret,
  participantBootstrapDumpSecretName,
  ExpectedValidatorOnboarding,
  validatorOnboardingSecretName,
  installValidatorOnboardingSecret,
  PersistenceConfig,
} from 'cn-pulumi-common';
import type { Auth0Client, CnInput, SvIdKey } from 'cn-pulumi-common';
import { jmxOptions } from 'cn-pulumi-common/src/jmx';

import * as postgres from './postgres';
import { GlobalDomainNode } from './globalDomainNode';
import { installParticipant } from './ledger';
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

export async function installSvNode(
  config: SvConfig,
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
    ? postgres.installPostgres(
        xns,
        config.splitPostgresInstances ? 'mediator-pg' : 'postgres',
        true
      )
    : sequencerPostgres;

  const globalDomain = new GlobalDomainNode('default', xns, sequencerPostgres, mediatorPostgres, {
    name: config.nodename,
    onboardingName: config.onboardingName,
    syncSource: cometBftSyncSource,
  });

  const participantPostgres = config.splitPostgresInstances
    ? postgres.installPostgres(xns, 'participant-pg', true)
    : sequencerPostgres;

  const participant = installParticipant(
    xns,
    'participant',
    participantPostgres,
    auth0UserNameEnvVarSource('sv'),
    // If we have a dump, we disable auto init.
    !!config.bootstrappingDumpConfig
  );

  const svAppPostgres = config.splitPostgresInstances
    ? postgres.installPostgres(xns, 'sv-pg', true)
    : sequencerPostgres;
  const svAppName = config.nodename.replaceAll('-', '_');
  const svDb = svAppPostgres.createDatabase(svAppName);
  const sequencerAddress = `${globalDomain.name}-sequencer`;

  const svValues = {
    onboardingType: config.onboarding.type,
    onboardingName: config.onboardingName,
    cometBFT: {
      enabled: true,
      connectionUri: `http://cometbft-${config.nodename}-cometbft-rpc:26657`,
    },
    globalDomainUrl: `http://${sequencerAddress}.sv-1:5008`,
    domain:
      // defaults for ports and address are fine,
      // we need to include a dummy value though
      // because helm does not distinguish between an empty object and unset.
      {
        sequencerAddress: sequencerAddress,
        mediatorAddress: `${globalDomain.name}-mediator`,
        sequencerPublicUrl: `https://sequencer.${config.nodename}.svc.${CLUSTER_BASENAME}.network.canton.global`,
        sequencerPruningConfig: config.sequencerPruningConfig,
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
    postgresSecretName: svAppPostgres.secretName,
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
  } as ChartValues;

  if (config.onboarding.type == 'join-with-key') {
    svValues.joinWithKeyOnboarding = {
      sponsorApiUrl: config.onboarding.sponsorApiUrl,
    };
  }

  const svApp = installCNHelmChart(xns, 'sv-app', 'cn-sv-node', svValues, {
    dependsOn: dependsOn.concat([participant, svAppPostgres, svDb, globalDomain]),
  });

  const scanAppPostgres = config.splitPostgresInstances
    ? postgres.installPostgres(xns, 'scan-pg', true)
    : sequencerPostgres;
  const scanDbName = `scan_${svAppName}`;
  const scanDb = scanAppPostgres.createDatabase(scanDbName);
  const scanValues = {
    clusterUrl: `${CLUSTER_BASENAME}.network.canton.global`,
    metrics: {
      enable: true,
    },
    persistence: persistenceConfig(scanAppPostgres, scanDbName),
    postgresSecretName: scanAppPostgres.secretName,
    additionalJvmOptions: jmxOptions(),
    sequencerAddress: sequencerAddress,
  };
  installCNHelmChart(xns, 'scan', 'cn-scan', scanValues, {
    dependsOn: [svApp, scanAppPostgres, scanDb, globalDomain],
  });

  const validatorPostgres = config.splitPostgresInstances
    ? postgres.installPostgres(xns, 'validator-pg', true)
    : sequencerPostgres;
  const validatorDbName = `validator_${svAppName}`;
  const validatorDb = validatorPostgres.createDatabase(validatorDbName);

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
  });

  installCNHelmChart(
    xns,
    'ingress-sv',
    'cn-cluster-ingress-runbook',
    {
      withSvIngress: true,
      ingress: {
        sequencer: {
          activeGlobalDomain: globalDomain.id,
        },
      },
      cluster: {
        hostname: `${CLUSTER_BASENAME}.network.canton.global`,
        svNamespace: xns.logicalName,
      },
    },
    { dependsOn: [xns.ns] }
  );

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
