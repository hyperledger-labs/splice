// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  activeVersion,
  Auth0Client,
  auth0UserNameEnvVarSource,
  DecentralizedSynchronizerUpgradeConfig,
  exactNamespace,
  imagePullSecretWithNonDefaultServiceAccount,
  installLedgerApiUserSecret,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  configForSv,
  installParticipant,
  StaticSvConfig,
  svConfigs,
  svRunbookConfig,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { StackReferences } from '@lfdecentralizedtrust/splice-pulumi-common/src/stackReferences';

export async function installNode(sv: string, auth0Client: Auth0Client): Promise<void> {
  const staticConfig = findStaticConfigOrFail(sv);
  const config = configForSv(staticConfig.nodeName);
  const xns = exactNamespace(staticConfig.nodeName, true, true);
  const serviceAccountName = 'sv';
  const imagePullDeps = imagePullSecretWithNonDefaultServiceAccount(xns, serviceAccountName);
  const auth0Config = auth0Client.getCfg();
  const ledgerApiUserSecret = installLedgerApiUserSecret(auth0Client, xns, 'sv', 'sv');
  const ledgerApiUserSecretSource = auth0UserNameEnvVarSource('sv', true);
  const participantMigrationInfo = config.migrateParticipantFromSvCantonToSv
    ? await getParticipantMigrationInfo(sv)
    : undefined;
  await installParticipant(
    {
      xns,
      participant: config.participant,
      logging: config.logging,
      auth0: auth0Config,
      version: config.versionOverride ?? activeVersion,
      disableProtection: staticConfig.nodeName === svRunbookConfig.nodeName,
      participantAdminUserNameFrom: ledgerApiUserSecretSource,
      imagePullServiceAccountName: serviceAccountName,
      migratingDatabaseInstanceName: participantMigrationInfo?.participantDatabaseId,
    },
    { dependsOn: [...imagePullDeps, ledgerApiUserSecret] }
  );
}

function findStaticConfigOrFail(sv: string): StaticSvConfig {
  const svConfig = svConfigs.concat([svRunbookConfig]).find(config => {
    return config.nodeName === sv;
  });
  if (svConfig === undefined) {
    throw new Error(`No sv config found for ${sv}`);
  } else {
    return svConfig;
  }
}

async function getParticipantMigrationInfo(sv: string): Promise<{ participantDatabaseId: string }> {
  const svCantonRef = StackReferences.svCanton(
    sv,
    DecentralizedSynchronizerUpgradeConfig.active.id
  );
  return {
    participantDatabaseId: await svCantonRef.getOutputValue('participantDatabaseId'),
  };
}
