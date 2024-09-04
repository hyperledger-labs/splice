import * as k8s from '@pulumi/kubernetes';
import { jsonStringify } from '@pulumi/pulumi';
import { ExactNamespace, svCometBftKeysFromSecret } from 'splice-pulumi-common';

const svCometBftKeys = svCometBftKeysFromSecret('sv-cometbft-keys');

const nodeKeyContent = {
  priv_key: {
    type: 'tendermint/PrivKeyEd25519',
    value: svCometBftKeys.nodePrivateKey,
  },
};
const validatorKeyContent = {
  address: '0647E4FF27908B8B874C2647536AC986C9EA0BAB',
  pub_key: {
    type: 'tendermint/PubKeyEd25519',
    value: svCometBftKeys.validatorPublicKey,
  },
  priv_key: {
    type: 'tendermint/PrivKeyEd25519',
    value: svCometBftKeys.validatorPrivateKey,
  },
};

export function installCometbftKeys(xns: ExactNamespace): void {
  new k8s.core.v1.Secret(
    'cometbft-keys',
    {
      metadata: {
        name: 'cometbft-keys',
        namespace: xns.logicalName,
      },
      type: 'Opaque',
      data: {
        'node_key.json': jsonStringify(nodeKeyContent).apply(s =>
          Buffer.from(s).toString('base64')
        ),
        'priv_validator_key.json': jsonStringify(validatorKeyContent).apply(s =>
          Buffer.from(s).toString('base64')
        ),
      },
    },
    { dependsOn: [xns.ns] }
  );
}
