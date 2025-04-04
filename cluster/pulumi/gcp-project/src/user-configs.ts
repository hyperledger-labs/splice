import * as gcp from '@pulumi/gcp';
import * as fs from 'fs';
import { Secret } from '@pulumi/gcp/secretmanager';
import { config } from 'splice-pulumi-common';

import { gcpProjectId, isMainNet, provider } from './consts';

function installUserConfigsForTenant(tenant: string): Secret {
  const userConfigsFile = `${config.requireEnv('CONFIGS_DIR')}/user-configs/${tenant}.us.auth0.com.json`;
  const secret = new gcp.secretmanager.Secret(`user-configs-${gcpProjectId}-${tenant}`, {
    secretId: `pulumi-user-configs-${tenant}`,
    replication: { auto: {} },
  });
  new gcp.secretmanager.SecretVersion(
    `user-configs-${gcpProjectId}-${tenant}-version`,
    {
      secret: secret.id,
      secretData: fs.readFileSync(userConfigsFile, 'utf-8'),
    },
    { provider, dependsOn: [secret] }
  );
  return secret;
}

export function installUserConfigs(): Secret[] {
  const mainNetTenants = ['canton-network-mainnet'];
  const nonMainNetTenants = ['canton-network-dev', 'canton-network-sv-test'];
  const tenants = isMainNet() ? mainNetTenants : nonMainNetTenants;
  const ret: Secret[] = [];
  tenants.forEach(tenant => {
    ret.push(installUserConfigsForTenant(tenant));
  });
  return ret;
}
