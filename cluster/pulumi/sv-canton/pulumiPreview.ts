import { runForAllMigrations } from './pulumi';

runForAllMigrations(async (stack, migration, sv) => {
  const preview = await stack.preview({
    parallel: 128,
    diff: true,
  });
  console.log(`[migration=${migration.migrationId}]Previewing stack for ${sv}`);
  console.error(preview.stderr);
  console.log(preview.stdout);
  console.log(JSON.stringify(preview.changeSummary));
}).catch(err => {
  console.error('Failed to run preview');
  console.error(err);
  process.exit(1);
});
