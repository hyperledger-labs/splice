import * as pulumi from '@pulumi/pulumi';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager/getSecretVersion';

export type SvIdKey = {
  publicKey: string;
  privateKey: string;
};

export function svKeyFromSecret(sv: string): pulumi.Output<SvIdKey> {
  const keyJson = getSecretVersionOutput({ secret: `${sv}-id` });
  return keyJson.apply(k => {
    const secretData = k.secretData;
    const parsed = JSON.parse(secretData);
    return {
      publicKey: String(parsed.publicKey),
      privateKey: String(parsed.privateKey),
    };
  });
}
