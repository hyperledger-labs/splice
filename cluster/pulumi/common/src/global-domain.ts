import { isDevNet } from './utils';

// TODO(#7344) remove once the driver is stable
export const disableCometBftDriver = process.env.DISABLE_COMETBFT_DRIVER === 'true';
export const globalDomainSequencerDriver =
  isDevNet && !disableCometBftDriver ? 'cometbft' : 'postgres';
