// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to be read here)
import {
  DecentralizedSynchronizerUpgradeConfig,
  DomainMigrationIndex,
} from '@canton-network/splice-pulumi-common';
import { allSvsToDeploy } from '@canton-network/splice-pulumi-common-sv';
import { StaticSvConfig } from '@canton-network/splice-pulumi-common-sv/src/config';

import {
  cantonNetworkAuth0Config,
  initDumpConfig,
  SecretsFixtureMap,
  svRunbookAuth0Config,
  withDumpConfigStack,
} from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig();
  const migrations = DecentralizedSynchronizerUpgradeConfig.allMigrations;
  for (let migrationIndex = 0; migrationIndex < migrations.length; migrationIndex++) {
    const migration = migrations[migrationIndex];
    await writeMigration(migration.id, allSvsToDeploy);
  }
}

async function writeMigration(migrationId: DomainMigrationIndex, svs: StaticSvConfig[]) {
  // eslint-disable-next-line no-process-env
  process.env.SPLICE_MIGRATION_ID = migrationId.toString();
  const installNode = await import('./src/installNode');
  const secrets = new SecretsFixtureMap();
  for (const sv of svs) {
    withDumpConfigStack(sv.nodeName, () =>
      installNode.installNode(migrationId, sv.nodeName, {
        getSecrets: () => Promise.resolve(secrets),
        /* eslint-disable @typescript-eslint/no-unused-vars */
        getClientAccessToken: (clientId: string, clientSecret: string, audience: string) =>
          Promise.resolve('access_token'),
        getCfg: () => (sv.nodeName === 'sv' ? svRunbookAuth0Config : cantonNetworkAuth0Config),
        reuseNamespaceConfig: (fromNamespace: string, toNamespace: string) => {},
      })
    );
  }
}

main().catch(e => {
  console.error(e.stack ?? e.message ?? e);
  process.exit(1);
});
