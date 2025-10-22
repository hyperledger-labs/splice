// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  BackupConfig,
  ChartValues,
  cnsUiSecret,
  config,
  exactNamespace,
  ExactNamespace,
  fixedTokens,
  setupBootstrapping,
  imagePullSecretByNamespaceName,
  installSpliceRunbookHelmChart,
  installSpliceRunbookHelmChartByNamespaceName,
  isDevNet,
  loadYamlFromFile,
  participantBootstrapDumpSecretName,
  SPLICE_ROOT,
  CLUSTER_BASENAME,
  CLUSTER_HOSTNAME,
  svKeySecret,
  svKeyFromSecret,
  validatorSecrets,
  ExpectedValidatorOnboarding,
  SvIdKey,
  imagePullSecret,
  CnInput,
  sequencerPruningConfig,
  DecentralizedSynchronizerMigrationConfig,
  ValidatorTopupConfig,
  svValidatorTopupConfig,
  svOnboardingPollingInterval,
  activeVersion,
  daContactPoint,
  spliceInstanceNames,
  DEFAULT_AUDIENCE,
  DecentralizedSynchronizerUpgradeConfig,
  ansDomainPrefix,
  svUserIds,
  SvCometBftGovernanceKey,
  svCometBftGovernanceKeySecret,
  svCometBftGovernanceKeyFromSecret,
  failOnAppVersionMismatch,
  networkWideConfig,
  getAdditionalJvmOptions,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  approvedSvIdentities,
  configForSv,
  installSvLoopback,
  svsConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { spliceConfig } from '@lfdecentralizedtrust/splice-pulumi-common/src/config/config';
import {
  CloudPostgres,
  SplicePostgres,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/postgres';

import { installRateLimits } from '../../common/src/ratelimit/rateLimit';
import { SvAppConfig, ValidatorAppConfig } from './config';
import { installCanton } from './decentralizedSynchronizer';
import { installPostgres } from './postgres';
import { svAppSecrets } from './utils';

if (!isDevNet) {
  console.error('Launching in non-devnet mode');
}

type BootstrapCliConfig = {
  cluster: string;
  date: string;
};

const bootstrappingConfig: BootstrapCliConfig = config.optionalEnv('BOOTSTRAPPING_CONFIG')
  ? JSON.parse(config.requireEnv('BOOTSTRAPPING_CONFIG'))
  : undefined;

const participantIdentitiesFile = config.optionalEnv('PARTICIPANT_IDENTITIES_FILE');
const decentralizedSynchronizerMigrationConfig = DecentralizedSynchronizerUpgradeConfig;

const initialAmuletPrice = config.optionalEnv('INITIAL_AMULET_PRICE');

export async function installNode(
  auth0Client: Auth0Client,
  svNamespaceStr: string,
  svAppConfig: SvAppConfig,
  validatorAppConfig: ValidatorAppConfig,
  resolveValidator1PartyId?: () => Promise<string>
): Promise<void> {
  console.error(
    activeVersion.type === 'local'
      ? 'Using locally built charts by default'
      : `Using charts from the artifactory by default, version ${activeVersion.version}`
  );
  console.error(`CLUSTER_BASENAME: ${CLUSTER_BASENAME}`);
  console.error(`Installing SV node in namespace: ${svNamespaceStr}`);

  const xns = exactNamespace(svNamespaceStr, true);

  const { participantBootstrapDumpSecret, backupConfigSecret, backupConfig } =
    await setupBootstrapping({
      xns,
      namespace: svNamespaceStr,
      CLUSTER_BASENAME,
      participantIdentitiesFile,
      bootstrappingConfig,
    });

  const loopback = installSvLoopback(xns);

  const imagePullDeps = imagePullSecret(xns);

  const svKey = svKeyFromSecret(svAppConfig.svIdKeyGcpSecret);

  const cometBftGovernanceKey = svAppConfig.externalGovernanceKeyGcpSecret
    ? svCometBftGovernanceKeyFromSecret(svAppConfig.externalGovernanceKeyGcpSecret)
    : undefined;

  const { sv, validator } = await installSvAndValidator(
    {
      xns,
      decentralizedSynchronizerMigrationConfig,
      participantBootstrapDumpSecret,
      auth0Client,
      imagePullDeps,
      loopback,
      backupConfigSecret,
      backupConfig,
      topupConfig: svValidatorTopupConfig,
      svKey,
      onboardingName: svAppConfig.onboardingName,
      validatorWalletUserName: validatorAppConfig.walletUserName,
      disableOnboardingParticipantPromotionDelay:
        svAppConfig.disableOnboardingParticipantPromotionDelay,
      cometBftGovernanceKey,
    },
    resolveValidator1PartyId
  );

  const ingressImagePullDeps = imagePullSecretByNamespaceName('cluster-ingress');

  if (svsConfig?.scan?.externalRateLimits) {
    installRateLimits(xns.logicalName, 'scan-app', 5012, svsConfig.scan.externalRateLimits);
  }

  installSpliceRunbookHelmChartByNamespaceName(
    xns.logicalName,
    xns.logicalName,
    'cluster-ingress-sv',
    'splice-cluster-ingress-runbook',
    {
      cluster: {
        hostname: CLUSTER_HOSTNAME,
        svNamespace: svNamespaceStr,
      },
      spliceDomainNames: {
        nameServiceDomain: ansDomainPrefix,
      },
      ingress: {
        decentralizedSynchronizer: {
          migrationIds: decentralizedSynchronizerMigrationConfig
            .runningMigrations()
            .map(x => x.id.toString()),
        },
      },
      rateLimit: {
        scan: {
          enable: false,
        },
      },
    },
    activeVersion,
    { dependsOn: ingressImagePullDeps.concat([sv, validator]) }
  );
}

type SvConfig = {
  auth0Client: Auth0Client;
  xns: ExactNamespace;
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig;
  onboarding?: ExpectedValidatorOnboarding;
  backupConfig?: BackupConfig;
  participantBootstrapDumpSecret?: pulumi.Resource;
  topupConfig?: ValidatorTopupConfig;
  imagePullDeps: CnInput<pulumi.Resource>[];
  loopback: pulumi.Resource[];
  backupConfigSecret?: pulumi.Resource;
  svKey: CnInput<SvIdKey>;
  onboardingName: string;
  validatorWalletUserName: string;
  disableOnboardingParticipantPromotionDelay: boolean;
  cometBftGovernanceKey?: CnInput<SvCometBftGovernanceKey>;
};

function persistenceForPostgres(pg: SplicePostgres | CloudPostgres, values: ChartValues) {
  return {
    persistence: {
      ...values?.persistence,
      host: pg.address,
      secretName: pg.secretName,
      postgresName: pg.instanceName,
    },
    enablePostgresMetrics: true,
  };
}

async function installSvAndValidator(
  config: SvConfig,
  resolveValidator1PartyId?: () => Promise<string>
) {
  const {
    xns,
    decentralizedSynchronizerMigrationConfig,
    participantBootstrapDumpSecret,
    topupConfig,
    auth0Client,
    imagePullDeps,
    backupConfigSecret,
    backupConfig,
    svKey,
    onboardingName,
    validatorWalletUserName,
    disableOnboardingParticipantPromotionDelay,
    cometBftGovernanceKey,
  } = config;

  const svConfig = configForSv('sv');
  const auth0Config = auth0Client.getCfg();
  const svNameSpaceAuth0Clients = auth0Config.namespaceToUiToClientId['sv'];
  if (!svNameSpaceAuth0Clients) {
    throw new Error('No SV namespace in auth0 config');
  }
  const svUiClientId = svNameSpaceAuth0Clients['sv'];
  if (!svUiClientId) {
    throw new Error('No SV ui client id in auth0 config');
  }

  const { appSecret: svAppSecret, uiSecret: svAppUISecret } = await svAppSecrets(
    xns,
    auth0Client,
    svUiClientId
  );

  svKeySecret(xns, svKey);

  const canton = installCanton(onboardingName, decentralizedSynchronizerMigrationConfig);

  const appsPg = installPostgres(xns, 'apps-pg', 'apps-pg-secret', 'postgres-values-apps.yaml');

  const bftSequencerConnection =
    !svConfig.participant || svConfig.participant.bftSequencerConnection;
  const topologyChangeDelayEnvVars = svsConfig?.synchronizer?.topologyChangeDelay
    ? [
        {
          name: 'ADDITIONAL_CONFIG_TOPOLOGY_CHANGE_DELAY',
          value: `canton.sv-apps.sv.topology-change-delay-duration=${svsConfig.synchronizer.topologyChangeDelay}`,
        },
      ]
    : [];
  const disableBftSequencerConnectionEnvVars = bftSequencerConnection
    ? []
    : [
        {
          name: 'ADDITIONAL_CONFIG_NO_BFT_SEQUENCER_CONNECTION',
          value: 'canton.sv-apps.sv.bft-sequencer-connection = false',
        },
      ];
  const svAppAdditionalEnvVars = (svConfig.svApp?.additionalEnvVars || [])
    .concat(topologyChangeDelayEnvVars)
    .concat(disableBftSequencerConnectionEnvVars);

  const valuesFromYamlFile = loadYamlFromFile(
    `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/sv-values.yaml`,
    {
      TARGET_HOSTNAME: CLUSTER_HOSTNAME,
      YOUR_SV_NAME: onboardingName,
      OIDC_AUTHORITY_URL: auth0Config.auth0Domain,
      YOUR_HOSTNAME: CLUSTER_HOSTNAME,
      MIGRATION_ID: decentralizedSynchronizerMigrationConfig.active.id.toString(),
      YOUR_CONTACT_POINT: daContactPoint,
    }
  );

  const extraBeneficiaries = resolveValidator1PartyId
    ? [
        {
          beneficiary: pulumi.Output.create(resolveValidator1PartyId()),
          weight: 3333,
        },
      ]
    : [];
  const svValues: ChartValues = {
    ...valuesFromYamlFile,
    participantIdentitiesDumpImport: participantBootstrapDumpSecret
      ? { secretName: participantBootstrapDumpSecretName }
      : undefined,
    approvedSvIdentities: approvedSvIdentities(),
    domain: {
      ...(valuesFromYamlFile.domain || {}),
      sequencerPruningConfig,
      skipInitialization:
        svsConfig?.synchronizer?.skipInitialization &&
        !svsConfig?.synchronizer.forceSvRunbookInitialization,
    },
    cometBFT: {
      ...(valuesFromYamlFile.cometBFT || {}),
      externalGovernanceKey: cometBftGovernanceKey
        ? true
        : valuesFromYamlFile.cometBFT?.externalGovernanceKey,
    },
    migration: {
      ...valuesFromYamlFile.migration,
      migrating: decentralizedSynchronizerMigrationConfig.isRunningMigration()
        ? true
        : valuesFromYamlFile.migration.migrating,
    },
    metrics: {
      enable: true,
    },
    ...spliceInstanceNames,
    extraBeneficiaries,
    onboardingPollingInterval: svOnboardingPollingInterval,
    disableOnboardingParticipantPromotionDelay,
    failOnAppVersionMismatch: failOnAppVersionMismatch,
    initialAmuletPrice: initialAmuletPrice,
    maxVettingDelay: networkWideConfig?.maxVettingDelay,
    logLevel: svConfig.logging?.appsLogLevel,
    additionalEnvVars: svAppAdditionalEnvVars,
    additionalJvmOptions: getAdditionalJvmOptions(svConfig.svApp?.additionalJvmOptions),
  };

  const svValuesWithSpecifiedAud: ChartValues = {
    ...svValues,
    ...persistenceForPostgres(appsPg, svValues),
    auth: {
      ...svValues.auth,
      audience: auth0Config.appToApiAudience['sv'] || DEFAULT_AUDIENCE,
    },
  };

  const fixedTokensValue: ChartValues = {
    cluster: {
      fixedTokens: true,
    },
  };

  const svValuesWithFixedTokens = {
    ...svValuesWithSpecifiedAud,
    ...fixedTokensValue,
  };

  const walletUiClientId = svNameSpaceAuth0Clients['wallet'];
  if (!walletUiClientId) {
    throw new Error('No SV ui client id in auth0 config');
  }
  const { appSecret: svValidatorAppSecret, uiSecret: svValidatorUISecret } = await validatorSecrets(
    xns,
    auth0Client,
    walletUiClientId
  );

  const sv = installSpliceRunbookHelmChart(
    xns,
    'sv-app',
    'splice-sv-node',
    fixedTokens() ? svValuesWithFixedTokens : svValuesWithSpecifiedAud,
    activeVersion,
    {
      dependsOn: imagePullDeps
        .concat(canton.participant.asDependencies)
        .concat(canton.decentralizedSynchronizer.dependencies)
        .concat([svAppSecret, svAppUISecret, appsPg])
        .concat(participantBootstrapDumpSecret ? [participantBootstrapDumpSecret] : [])
        .concat(
          cometBftGovernanceKey ? svCometBftGovernanceKeySecret(xns, cometBftGovernanceKey) : []
        ),
    }
  );

  const defaultScanValues = loadYamlFromFile(
    `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/scan-values.yaml`,
    {
      TARGET_HOSTNAME: CLUSTER_HOSTNAME,
      MIGRATION_ID: decentralizedSynchronizerMigrationConfig.active.id.toString(),
    }
  );
  const scanValues: ChartValues = {
    ...defaultScanValues,
    ...persistenceForPostgres(appsPg, defaultScanValues),
    ...spliceInstanceNames,
    metrics: {
      enable: true,
    },
  };

  const scanValuesWithFixedTokens = {
    ...scanValues,
    ...fixedTokensValue,
  };

  installSpliceRunbookHelmChart(
    xns,
    'scan',
    'splice-scan',
    fixedTokens() ? scanValuesWithFixedTokens : scanValues,
    activeVersion,
    {
      dependsOn: imagePullDeps
        .concat(canton.participant.asDependencies)
        .concat([svAppSecret, appsPg])
        .concat(spliceConfig.pulumiProjectConfig.interAppsDependencies ? [sv] : []),
    }
  );

  const validatorValues = {
    ...loadYamlFromFile(`${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/validator-values.yaml`, {
      TARGET_HOSTNAME: CLUSTER_HOSTNAME,
      OPERATOR_WALLET_USER_ID: validatorWalletUserName,
      OIDC_AUTHORITY_URL: auth0Config.auth0Domain,
      TRUSTED_SCAN_URL: `http://scan-app.${xns.logicalName}:5012`,
      YOUR_CONTACT_POINT: daContactPoint,
    }),
    ...loadYamlFromFile(
      `${SPLICE_ROOT}/apps/app/src/pack/examples/sv-helm/sv-validator-values.yaml`,
      {
        TARGET_HOSTNAME: CLUSTER_HOSTNAME,
        MIGRATION_ID: decentralizedSynchronizerMigrationConfig.active.id.toString(),
        YOUR_SV_NAME: onboardingName,
      }
    ),
    metrics: {
      enable: true,
    },
    participantIdentitiesDumpPeriodicBackup: backupConfig,
    validatorWalletUsers: svUserIds(auth0Config).apply(ids =>
      ids.concat([validatorWalletUserName])
    ),
    ...spliceInstanceNames,
    maxVettingDelay: networkWideConfig?.maxVettingDelay,
  };

  const validatorValuesWithSpecifiedAud: ChartValues = {
    ...validatorValues,
    ...persistenceForPostgres(appsPg, validatorValues),
    auth: {
      ...validatorValues.auth,
      audience: auth0Config.appToApiAudience['validator'] || DEFAULT_AUDIENCE,
    },
  };

  const validatorValuesWithMaybeFixedTokens: ChartValues = {
    ...validatorValuesWithSpecifiedAud,
    ...(fixedTokens() ? fixedTokensValue : {}),
  };

  const validatorValuesWithMaybeTopups: ChartValues = {
    ...validatorValuesWithMaybeFixedTokens,
    topup: topupConfig ? { enabled: true, ...topupConfig } : { enabled: false },
  };

  const validatorValuesWithMaybeNoBftSequencerConnection: ChartValues = {
    ...validatorValuesWithMaybeTopups,
    ...(bftSequencerConnection
      ? {}
      : {
          decentralizedSynchronizerUrl: svValues.decentralizedSynchronizerUrl,
          useSequencerConnectionsFromScan: false,
        }),
    additionalEnvVars: [
      ...(validatorValuesWithMaybeTopups.additionalEnvVars || []),
      ...(bftSequencerConnection
        ? []
        : [
            {
              name: 'ADDITIONAL_CONFIG_NO_BFT_SEQUENCER_CONNECTION',
              value:
                'canton.validator-apps.validator_backend.disable-sv-validator-bft-sequencer-connection = true',
            },
          ]),
      ...(svConfig.validatorApp?.additionalEnvVars || []),
    ],
    additionalJvmOptions: getAdditionalJvmOptions(svConfig.validatorApp?.additionalJvmOptions),
  };

  const cnsUiClientId = svNameSpaceAuth0Clients['cns'];
  if (!cnsUiClientId) {
    throw new Error('No CNS ui client id in auth0 config');
  }

  const validator = installSpliceRunbookHelmChart(
    xns,
    'validator',
    'splice-validator',
    validatorValuesWithMaybeNoBftSequencerConnection,
    activeVersion,
    {
      dependsOn: imagePullDeps
        .concat(canton.participant.asDependencies)
        .concat([svValidatorAppSecret, svValidatorUISecret])
        .concat(spliceConfig.pulumiProjectConfig.interAppsDependencies ? [sv] : [])
        .concat([cnsUiSecret(xns, auth0Client, cnsUiClientId)])
        .concat(backupConfigSecret ? [backupConfigSecret] : [])
        .concat([appsPg]),
    }
  );

  return { sv, validator };
}
