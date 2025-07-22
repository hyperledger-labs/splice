// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as fs from 'fs';
import { Secret } from '@pulumi/gcp/secretmanager';
import { config } from 'splice-pulumi-common';

import { authorizeServiceAccount } from './authorizeServiceAccount';
import { gcpProjectConfig, configsDir } from './config';

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
    const ipsFromConfig = gcpProjectConfig.ips;
    const ips: string[] = ipsFromConfig['All Clusters'].concat(
      this.isMainNet() ? [] : ipsFromConfig['Non-MainNet']
    );
    return this.secretAndVersion('internal-whitelists', JSON.stringify(ips));
  }

  private userConfigsForTenant(tenant: string): Secret {
    const userConfigsFile = `${configsDir}/user-configs/${tenant}.us.auth0.com.json`;
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
    const val = gcpProjectConfig.letsEncrypt.email;
    return this.secretAndVersion('lets-encrypt-email', val);
  }

  private authorizedServiceAccount(): gcp.projects.IAMMember[] {
    if (!gcpProjectConfig.authorizedServiceAccount) {
      console.warn(
        'AUTHORIZED_SERVICE_ACCOUNT is not set; this is only fine for a project never touched by CircleCI flows.'
      );
      return [];
    }
    const authorizedServiceAccountConfig = {
      serviceAccountEmail: gcpProjectConfig.authorizedServiceAccount.email,
      pulumiKeyringProjectId: config.requireEnv('PULUMI_BACKEND_GCPKMS_PROJECT'),
      pulumiKeyringRegion: config.requireEnv('CLOUDSDK_COMPUTE_REGION'),
    };
    return authorizeServiceAccount(this.gcpProjectId, authorizedServiceAccountConfig);
  }

  constructor(gcpProjectId: string) {
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
    this.authorizedServiceAccount();
  }
}
