// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to be read here)
import {
  DecentralizedSynchronizerUpgradeConfig,
  DomainMigrationIndex,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import { allSvsToDeploy } from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { StaticSvConfig } from '@lfdecentralizedtrust/splice-pulumi-common-sv/src/config';

import {
  cantonNetworkAuth0Config,
  initDumpConfig,
  SecretsFixtureMap,
  svRunbookAuth0Config,
} from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig();
  const migrations = DecentralizedSynchronizerUpgradeConfig.allMigrations;
  /**
   * Ideally we would've outputted every migration to it's own json object (or even better, it's own file).
   * But we seem to have no control over when is the whole output written, as it's fully async so there's no easy way to manage the json output.
   * Outputting to a different file is also a pain, as currently it's handled in the make files. We would either need to change the make logic to be aware of migrations so that it runs
   * the dump-config for each sv/migration (don't really see any sane way of doing this), or we would need to move the file writing directly in the typescript code
   * (this sounds like the sanest approach but it would require a lot more changes)
   * */
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
    installNode.installNode(migrationId, sv.nodeName, {
      getSecrets: () => Promise.resolve(secrets),
      /* eslint-disable @typescript-eslint/no-unused-vars */
      getClientAccessToken: (clientId: string, clientSecret: string, audience: string) =>
        Promise.resolve('access_token'),
      getCfg: () => (sv.nodeName === 'sv' ? svRunbookAuth0Config : cantonNetworkAuth0Config),
      reuseNamespaceConfig: (fromNamespace: string, toNamespace: string) => {},
    });
  }
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
