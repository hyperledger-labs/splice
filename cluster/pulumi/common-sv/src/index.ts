import { config, isDevNet } from 'splice-pulumi-common';

import { svConfigs } from './svConfigs';

export * from './synchronizer/cometbft';
export * from './synchronizer/cometbftConfig';
export * from './synchronizer/cometBftNodeConfigs';
export * from './synchronizer/decentralizedSynchronizerNode';
export * from './canton';
export * from './participant';
export * from './auth0';
export * from './svConfigs';

export function getDsoSize(): number {
  // If not devnet, enforce 1 sv
  if (!isDevNet) {
    return 1;
  }

  const maxDsoSize = svConfigs.length;
  const dsoSize = +config.requireEnv(
    'DSO_SIZE',
    `Specify how many foundation SV nodes this cluster should be deployed with. (min 1, max ${maxDsoSize})`
  );

  if (dsoSize < 1) {
    throw new Error('DSO_SIZE must be at least 1');
  }

  if (dsoSize > maxDsoSize) {
    throw new Error(`DSO_SIZE must be at most ${maxDsoSize}`);
  }

  return dsoSize;
}
