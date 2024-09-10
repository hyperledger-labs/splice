// Need to import this by path and not through the module, so the module is not
// initialized when we don't want it to (to avoid pulumi configs trying to be read here)
import {
  config,
  DecentralizedSynchronizerMigrationConfig,
  DomainMigrationIndex,
  MigrationProvider,
} from 'splice-pulumi-common';
import { getDsoSize, svConfigs, svRunbookConfig } from 'splice-pulumi-common-sv';
import { StaticSvConfig } from 'splice-pulumi-common-sv/src/config';

import { initDumpConfig } from '../common/src/dump-config-common';

async function main() {
  await initDumpConfig();
  const migrationConfig = DecentralizedSynchronizerMigrationConfig.fromEnv();
  const externalMigrations = migrationConfig
    .allMigrationInfos()
    .filter(migration => migration.provider === MigrationProvider.EXTERNAL);
  const dsoSize = getDsoSize();
  const deployRunbook = config.envFlag('SPLICE_DEPLOY_SV_RUNBOOK', false);
  const allSvs = svConfigs.slice(0, dsoSize).concat(deployRunbook ? [svRunbookConfig] : []);
  /**
   * Ideally we would've outputted every migration to it's own json object (or even better, it's own file).
   * But we seem to have no control over when is the whole output written, as it's fully async so there's no easy way to manage the json output.
   * Outputting to a different file is also a pain, as currently it's handled in the make files. We would either need to change the make logic to be aware of migrations so that it runs
   * the dump-config for each sv/migration (don't really see any sane way of doing this), or we would need to move the file writing directly in the typescript code
   * (this sounds like the sanest approach but it would require a lot more changes)
   * */
  for (let migrationIndex = 0; migrationIndex < externalMigrations.length; migrationIndex++) {
    const migration = externalMigrations[migrationIndex];
    await writeMigration(migration.migrationId, allSvs);
  }
}

async function writeMigration(migrationId: DomainMigrationIndex, svs: StaticSvConfig[]) {
  process.env.SPLICE_MIGRATION_ID = migrationId.toString();
  const installNode = await import('./src/installNode');
  for (const sv of svs) {
    installNode.installNode(migrationId, sv.nodeName, false);
  }
}

main();
