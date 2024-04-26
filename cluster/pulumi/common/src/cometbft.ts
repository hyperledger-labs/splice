import { config } from './config';

const enableCometbftPruning = config.envFlag('ENABLE_COMETBFT_PRUNING', true);
export const cometbftRetainBlocks = enableCometbftPruning
  ? parseInt(config.requireEnv('COMETBFT_RETAIN_BLOCKS'))
  : 0; // 0 implies retain all blocks

export const disableCometBftStateSync = config.envFlag('DISABLE_COMETBFT_STATE_SYNC', false);
export const stableCometBftChainId = config.envFlag('COMETBFT_STABLE_CHAIN_ID', false);
