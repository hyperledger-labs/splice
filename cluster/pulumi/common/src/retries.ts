import * as pulumi from '@pulumi/pulumi';

export async function retry<T>(
  name: string,
  delayMs: number,
  retries: number,
  action: () => Promise<T>
): Promise<T> {
  try {
    return await action();
  } catch (e) {
    await pulumi.log.error(`Failed '${name}'. Error: ${JSON.stringify(e)}.`);
    if (retries > 0) {
      await new Promise(resolve => setTimeout(resolve, delayMs));
      return await retry(name, delayMs, retries - 1, action);
    } else {
      return Promise.reject(`Exhausted retries. Last error: ${e}.`);
    }
  }
}
