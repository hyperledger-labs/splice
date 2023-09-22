import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import {
  auth0UserNameEnvVarSource,
  BackupConfig,
  BootstrappingDumpConfig,
  ChartValues,
  CLUSTER_BASENAME,
  ExactNamespace,
  exactNamespace,
  fetchAndInstallParticipantBootstrapDump,
  getLatestSvcAcsDumpFile,
  installAuth0Secret,
  installAuth0UISecret,
  installCNHelmChart,
  installGcpBucketSecret,
  participantBootstrapDumpSecretName,
} from 'cn-pulumi-common';
import type { Auth0Client } from 'cn-pulumi-common';

import * as postgres from './postgres';
import { installCometBftNode } from './cometbft';
import { installGlobalDomain, installParticipant } from './ledger';
import { installValidatorApp } from './validator';

// btoa is only available in DOM so inline the definition here.
const btoa = (s: string) => Buffer.from(s).toString('base64');

export function installSvKeySecret(
  xns: ExactNamespace,
  publicKey: string,
  privateKey: string
): k8s.core.v1.Secret {
  const secretName = 'cn-app-sv-key';
  return new k8s.core.v1.Secret(
    `cn-app-${xns.logicalName}-key`,
    {
      metadata: {
        name: secretName,
        namespace: xns.logicalName,
      },
      type: 'Opaque',
      data: {
        public: btoa(publicKey),
        private: btoa(privateKey),
      },
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
      publicKey: string;
      privateKey: string;
      sequencerDatabase: postgres.Postgres;
      sponsorRelease?: pulumi.Resource;
      sponsorApiUrl: string;
    };

export type ValidatorOnboarding = { name: string; expiresIn: string; secret: string };

export type ApprovedSvIdentity = { name: string; publicKey: string };

export const validatorOnboardingSecretName = (onboarding: ValidatorOnboarding): string =>
  `cn-app-validator-onboarding-${onboarding.name}`;

export function installValidatorOnboardingSecret(
  xns: ExactNamespace,
  onboarding: ValidatorOnboarding
): k8s.core.v1.Secret {
  const secretName = validatorOnboardingSecretName(onboarding);
  return new k8s.core.v1.Secret(
    `cn-app-${xns.logicalName}-validator-onboarding-${onboarding.name}`,
    {
      metadata: {
        name: secretName,
        namespace: xns.logicalName,
      },
      type: 'Opaque',
      data: {
        secret: btoa(onboarding.secret),
      },
    },
    {
      dependsOn: [xns.ns],
    }
  );
}

export type SvConfig = {
  auth0Client: Auth0Client;
  nodename: string;
  onboardingName: string;
  validatorWalletUser: string;
  onboarding: SvOnboarding;
  withDomainFees: boolean;
  approvedSvIdentities: ApprovedSvIdentity[];
  withScan: boolean;
  withDirectoryBackend: boolean;
  expectedValidatorOnboardings: ValidatorOnboarding[];
  isDevNet: boolean;
  backupConfig?: BackupConfig;
  bootstrappingDumpConfig?: BootstrappingDumpConfig;
  withDomainNode: boolean;
  auth0ValidatorAppName: string;
  sequencerDriver: 'cometbft' | 'postgres';
};

async function getAcsBootstrappingDump(xns: ExactNamespace, config: BootstrappingDumpConfig) {
  const file = await getLatestSvcAcsDumpFile(xns, config);
  return {
    path: file.name,
    bucket: config.bucket,
  };
}

export function installSvNode(config: SvConfig): {
  svApp: pulumi.Resource;
  postgresDatabase: postgres.Postgres;
} {
  const xns = exactNamespace(config.nodename);

  const auth0BackendSecrets: pulumi.Resource[] = [
    installAuth0Secret(config.auth0Client, xns, 'sv', config.nodename),
  ];

  const auth0UISecrets: pulumi.Resource[] = [
    installAuth0UISecret(config.auth0Client, xns, 'sv', config.nodename),
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
    ? installGcpBucketSecret(xns, config.backupConfig.bucket)
    : undefined;

  const participantBootstrapDumpSecret: pulumi.Resource | undefined = config.bootstrappingDumpConfig
    ? fetchAndInstallParticipantBootstrapDump(xns, config.bootstrappingDumpConfig)
    : undefined;

  const dependsOn: pulumi.Resource[] = auth0BackendSecrets
    .concat(auth0UISecrets)
    .concat(
      config.onboarding.type == 'join-with-key'
        ? [installSvKeySecret(xns, config.onboarding.publicKey, config.onboarding.privateKey)]
        : []
    )
    .concat(
      config.onboarding.type == 'join-with-key' && config.onboarding.sponsorRelease
        ? [config.onboarding.sponsorRelease]
        : []
    )
    .concat(
      config.expectedValidatorOnboardings.map(onboarding =>
        installValidatorOnboardingSecret(xns, onboarding)
      )
    )
    .concat(backupConfigSecret ? [backupConfigSecret] : [])
    .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : []);

  const postgresDb = postgres.installPostgres(xns, 'postgres');
  const cometBftRpcService = installCometBftNode(xns, config.nodename, config.onboardingName);

  const domain = undefined;

  if (config.withDomainNode) {
    const sequencerDatabase =
      config.onboarding.type === 'join-with-key' ? config.onboarding.sequencerDatabase : postgresDb;

    installGlobalDomain(
      xns,
      'global-domain',
      config.withDomainFees,
      postgresDb,
      config.sequencerDriver === 'cometbft'
        ? {
            driver: 'cometbft',
            service: cometBftRpcService,
          }
        : {
            driver: 'postgres',
            postgres: sequencerDatabase,
          }
    );
  }

  const participant = installParticipant(
    xns,
    'participant',
    postgresDb,
    auth0UserNameEnvVarSource('sv'),
    // If we have a dump, we disable auto init.
    !!config.bootstrappingDumpConfig
  );

  const svValues = {
    onboardingType: config.onboarding.type,
    onboardingName: config.onboardingName,
    cometBFT: {
      enabled: true,
      connectionUri: `http://cometbft-${config.nodename}-cometbft-rpc:26657`,
    },
    globalDomainUrl: 'http://global-domain-sequencer.sv-1:5008',
    domain:
      // defaults for ports and address are fine,
      // we need to include a dummy value though
      // because helm does not distinguish between an empty object and unset.
      { enable: config.withDomainNode },
    expectedValidatorOnboardings: config.expectedValidatorOnboardings.map(onboarding => ({
      expiresIn: onboarding.expiresIn,
      secretFrom: {
        secretKeyRef: {
          name: validatorOnboardingSecretName(onboarding),
          key: 'secret',
          optional: false,
        },
      },
    })),
    isDevNet: config.isDevNet,
    approvedSvIdentities: config.approvedSvIdentities,
    acsDumpPeriodicExport: backupConfig,
    acsDumpImport:
      config.bootstrappingDumpConfig && config.onboarding.type === 'found-collective'
        ? getAcsBootstrappingDump(xns, config.bootstrappingDumpConfig)
        : undefined,
    participantIdentitiesDumpImport: config.bootstrappingDumpConfig
      ? { secretName: participantBootstrapDumpSecretName }
      : undefined,
  } as ChartValues;

  if (config.onboarding.type == 'join-with-key') {
    svValues.joinWithKeyOnboarding = {
      sponsorApiUrl: config.onboarding.sponsorApiUrl,
    };
  }

  const svApp = installCNHelmChart(
    xns,
    config.nodename + '-sv-app',
    'cn-sv-node',
    svValues,
    dependsOn.concat([participant, cometBftRpcService].concat(domain ? [domain] : []))
  );

  if (config.onboarding.type == 'found-collective' && !config.withScan) {
    console.error('Founding node always needs to have CC Scan enabled');
    process.exit(1);
  }

  if (config.withScan) {
    const scanApp = installCNHelmChart(xns, 'scan-' + xns.logicalName, 'cn-scan', {}, [svApp]);
    if (config.onboarding.type == 'found-collective') {
      installCNHelmChart(xns, 'directory-' + xns.logicalName, 'cn-directory', {}, [scanApp]);
    }
  }

  installValidatorApp({
    auth0Client: config.auth0Client,
    withDomainFees: config.withDomainFees,
    xns,
    validatorWalletUser: config.validatorWalletUser,
    participant,
    disableAllocateLedgerApiUserParty: true,
    auth0AppName: config.auth0ValidatorAppName,
    backupConfig:
      backupConfig && backupConfigSecret
        ? {
            config: backupConfig,
            secret: backupConfigSecret,
          }
        : undefined,
    extraDependsOn: [svApp],
  });

  installCNHelmChart(
    xns,
    'ingress-sv-' + xns.logicalName,
    'cn-cluster-ingress-sv',
    {
      withScan: config.withScan,
      withDirectoryBackend: config.withDirectoryBackend,
      cluster: {
        hostname: `${CLUSTER_BASENAME}.network.canton.global`,
        svNamespace: xns.logicalName,
      },
    },
    [xns.ns]
  );

  return { svApp, postgresDatabase: postgresDb };
}
