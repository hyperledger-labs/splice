// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import * as fs from 'fs';
import { Secret } from '@pulumi/gcp/secretmanager';
import { config, loadYamlFromFile } from 'splice-pulumi-common';

import { authorizeServiceAccount } from './authorizeServiceAccount';

export class GcpProject extends pulumi.ComponentResource {
  gcpProjectId: string;
  configsDir: string;

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
    const whitelistsFile = `${this.configsDir}/ips.yaml`;
    const ipsFromFile = loadYamlFromFile(whitelistsFile);
    const ips: string[] = ipsFromFile['All Clusters'].concat(
      this.isMainNet() ? [] : ipsFromFile['Non-MainNet']
    );
    return this.secretAndVersion('internal-whitelists', JSON.stringify(ips));
  }

  private userConfigsForTenant(tenant: string): Secret {
    const userConfigsFile = `${this.configsDir}/user-configs/${tenant}.us.auth0.com.json`;
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
    const val = fs.readFileSync(`${this.configsDir}/lets-encrypt-email.txt`, 'utf-8').trim();
    return this.secretAndVersion('lets-encrypt-email', val);
  }

  private authorizedServiceAccount(): gcp.projects.IAMMember[] {
    // TODO de-hardcode this
    const authorizedServiceAccountEmail = "mock-service-account@mock.com";

    const pulumiKeyringProjectId = config.requireEnv('PULUMI_BACKEND_GCPKMS_PROJECT');
    if (!pulumiKeyringProjectId) {
      throw new Error('PULUMI_BACKEND_GCPKMS_PROJECT is undefined');
    }
    const pulumiKeyringRegion = config.requireEnv('CLOUDSDK_COMPUTE_REGION');
    if (!pulumiKeyringRegion) {
      throw new Error('CLOUDSDK_COMPUTE_REGION is undefined');
    }
    const authorizedServiceAccountConfig = {
      serviceAccountEmail: authorizedServiceAccountEmail,
      pulumiKeyringProjectId,
      pulumiKeyringRegion,
    };
    return authorizeServiceAccount(this.gcpProjectId, authorizedServiceAccountConfig);
    // console.warn(
    //   'AUTHORIZED_SERVICE_ACCOUNT is not set; this is only fine for a project never touched by CircleCI flows.'
    // );
    // return undefined;
  }

  constructor(gcpProjectId: string, configsDir: string) {
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
    this.configsDir = configsDir;
    this.internalWhitelists();
    this.userConfigs();
    this.letsEncryptEmail();
    this.authorizedServiceAccount();
  }
}
