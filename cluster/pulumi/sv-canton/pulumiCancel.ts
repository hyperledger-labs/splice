import { awaitAllOrThrowAllExceptions } from '../pulumi';
import { runSvCantonForAllMigrations } from './pulumi';

export async function cancelAllCantonStacks(): Promise<void> {
  await awaitAllOrThrowAllExceptions(
    runSvCantonForAllMigrations(
      stack => {
        return stack.cancel();
      },
      false,
      true
    )
  );
}
