import { awaitAllOrThrowAllExceptions, ensureStackSettingsAreUpToDate } from '../pulumi';
import { runSvCantonForAllMigrations } from './pulumi';

awaitAllOrThrowAllExceptions(
  runSvCantonForAllMigrations(
    'preview',
    async (stack, migration, sv) => {
      await ensureStackSettingsAreUpToDate(stack);
      const preview = await stack.preview({
        parallel: 128,
        diff: true,
      });
      console.log(`[migration=${migration.id}]Previewing stack for ${sv}`);
      console.error(preview.stderr);
      console.log(preview.stdout);
      console.log(JSON.stringify(preview.changeSummary));
    },
    true,
    true
  )
).catch(err => {
  console.error('Failed to run preview');
  console.error(err);
  process.exit(1);
});
