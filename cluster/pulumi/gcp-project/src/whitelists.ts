import * as gcp from '@pulumi/gcp';
import { Secret } from '@pulumi/gcp/secretmanager';
import { config, loadYamlFromFile } from 'splice-pulumi-common';

import { isMainNet, provider } from './consts';

export function installInternalWhitelists(): Secret {
  const whitelistsFile = `${config.requireEnv('CONFIGS_DIR')}/ips.yaml`;
  const ipsFromFile = loadYamlFromFile(whitelistsFile);
  const ips: string[] = ipsFromFile['All Clusters'].concat(
    isMainNet() ? [] : ipsFromFile['Non-MainNet']
  );

  const secret = new gcp.secretmanager.Secret(
    'internal-whitelist',
    {
      secretId: 'pulumi-internal-whitelists',
      replication: { auto: {} },
    },
    { provider }
  );
  new gcp.secretmanager.SecretVersion(
    'internal-whitelist-version',
    {
      secret: secret.id,
      secretData: JSON.stringify(ips),
    },
    { provider: provider, dependsOn: [secret] }
  );
  return secret;
}
