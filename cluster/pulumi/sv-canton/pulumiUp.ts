import { pulumiOpts, runForAllMigrations } from './pulumi';

runForAllMigrations(async (stack, migration, sv) => {
  console.log(`[migration=${migration.migrationId}]Updating stack for ${sv}`);
  await stack.refresh(pulumiOpts);
  const result = await stack.up(pulumiOpts);
  console.log(`[migration=${migration.migrationId}]Updated stack for ${sv}`);
  console.log(JSON.stringify(result.summary));
}, false).catch(err => {
  console.error('Failed to run up');
  console.error(err);
  process.exit(1);
});
