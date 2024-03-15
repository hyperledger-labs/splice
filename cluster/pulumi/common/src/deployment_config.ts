import { envFlag, requireEnv } from './utils';

const enableCometbftPruning = envFlag('ENABLE_COMETBFT_PRUNING', true);
export const cometbftRetainBlocks = enableCometbftPruning
  ? parseInt(requireEnv('COMETBFT_RETAIN_BLOCKS'))
  : 0; // 0 implies retain all blocks
