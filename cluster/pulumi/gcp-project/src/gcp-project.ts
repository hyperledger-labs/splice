// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as fs from 'fs';
import { Secret } from '@pulumi/gcp/secretmanager';
import { config, loadYamlFromFile } from 'splice-pulumi-common';

import {
  authorizeServiceAccount,
  ServiceAccountAuthorizationConfig,
} from './authorizeServiceAccount';

export class GcpProject extends pulumi.ComponentResource {
  gcpProjectId: string;

  private isMainNet(): boolean {
    // We check also the GCP_CLUSTER_BASENAME for update-expected, because in dump-config we overwrite the project id
    return (
      this.gcpProjectId === 'da-cn-mainnet' ||
      config.optionalEnv('GCP_CLUSTER_BASENAME') === 'mainzrh'
    );
  }

  private secretAndVersion(name: string, value: string): Secret {
    const secret = new gcp.secretmanager.Secret(name, {
      secretId: `pulumi-${name}`,
      replication: { auto: {} },
    });
    new gcp.secretmanager.SecretVersion(
      `${name}-version`,
      {
        secret: secret.id,
        secretData: value,
      },
      { dependsOn: [secret] }
    );
    return secret;
  }

  private internalWhitelists(): Secret {
    const whitelistsFile = `${config.requireEnv('CONFIGS_DIR')}/ips.yaml`;
    const ipsFromFile = loadYamlFromFile(whitelistsFile);
    const ips: string[] = ipsFromFile['All Clusters'].concat(
      this.isMainNet() ? [] : ipsFromFile['Non-MainNet']
    );
    return this.secretAndVersion('internal-whitelists', JSON.stringify(ips));
  }

  private userConfigsForTenant(tenant: string): Secret {
    const userConfigsFile = `${config.requireEnv('CONFIGS_DIR')}/user-configs/${tenant}.us.auth0.com.json`;
    return this.secretAndVersion(
      `user-configs-${tenant}`,
      fs.readFileSync(userConfigsFile, 'utf-8')
    );
  }

  private userConfigs(): Secret[] {
    const mainNetTenants = ['canton-network-mainnet'];
    const nonMainNetTenants = ['canton-network-dev', 'canton-network-sv-test'];
    const tenants = this.isMainNet() ? mainNetTenants : nonMainNetTenants;
    const ret: Secret[] = [];
    tenants.forEach(tenant => {
      ret.push(this.userConfigsForTenant(tenant));
    });
    return ret;
  }

  private letsEncryptEmail(): Secret {
    const val = fs
      .readFileSync(`${config.requireEnv('CONFIGS_DIR')}/lets-encrypt-email.txt`, 'utf-8')
      .trim();
    return this.secretAndVersion('lets-encrypt-email', val);
  }

  constructor(gcpProjectId: string, authorizedServiceAccount?: ServiceAccountAuthorizationConfig) {
    super(
      'cn:gcp:project',
      'gcp-project',
      {},
      {
        provider: new gcp.Provider(`provider-${gcpProjectId}`, {
          project: gcpProjectId,
        }),
      }
    );
    this.gcpProjectId = gcpProjectId;
    this.internalWhitelists();
    this.userConfigs();
    this.letsEncryptEmail();
    authorizedServiceAccount
      ? authorizeServiceAccount(gcpProjectId, authorizedServiceAccount)
      : undefined;
  }
}
