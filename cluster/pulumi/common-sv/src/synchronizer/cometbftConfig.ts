import { Output } from '@pulumi/pulumi';
import { config } from 'splice-pulumi-common/src/config';

const enableCometbftPruning = config.envFlag('ENABLE_COMETBFT_PRUNING', true);
export const cometbftRetainBlocks = enableCometbftPruning
  ? parseInt(config.requireEnv('COMETBFT_RETAIN_BLOCKS'))
  : 0; // 0 implies retain all blocks

export const disableCometBftStateSync = config.envFlag('DISABLE_COMETBFT_STATE_SYNC', false);

export type StaticCometBftConfig = {
  privateKey?: Output<string> | string;
  validator: {
    keyAddress: Output<string> | string;
    privateKey?: Output<string> | string;
    publicKey?: Output<string> | string;
  };
  nodeIndex: number;
  retainBlocks: number;
  id: string;
};

export interface StaticCometBftConfigWithNodeName extends StaticCometBftConfig {
  nodeName: string;
}
