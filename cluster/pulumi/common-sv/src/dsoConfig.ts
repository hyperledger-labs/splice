import { config, isDevNet } from 'splice-pulumi-common/src/config';

function getDsoSize(): number {
  // If not devnet, enforce 1 sv
  if (!isDevNet) {
    return 1;
  }

  const maxDsoSize = 16;
  const dsoSize = parseInt(
    config.requireEnv(
      'DSO_SIZE',
      `Specify how many foundation SV nodes this cluster should be deployed with. (min 1, max ${maxDsoSize})`
    )
  );

  if (dsoSize < 1) {
    throw new Error('DSO_SIZE must be at least 1');
  }

  if (dsoSize > maxDsoSize) {
    throw new Error(`DSO_SIZE must be at most ${maxDsoSize}`);
  }

  return dsoSize;
}

export const dsoSize = getDsoSize();
