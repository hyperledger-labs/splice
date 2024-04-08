import { envFlag } from './utils';

export const disableCometBftStateSync = envFlag('DISABLE_COMETBFT_STATE_SYNC', false);
export const stableCometBftChainId = envFlag('COMETBFT_STABLE_CHAIN_ID', false);
