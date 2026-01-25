// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import { config, GcpServiceAccount } from '@lfdecentralizedtrust/splice-pulumi-common';

import { ImportedSecret } from './importedSecret';

class GcpProject extends pulumi.ComponentResource {
  opts?: pulumi.CustomResourceOptions;
  secretmanager: gcp.projects.Service;

  private enableService(service: string): gcp.projects.Service {
    return new gcp.projects.Service(
      service,
      { disableDependentServices: true, service: `${service}.googleapis.com` },
      this.opts
    );
  }

  private importSecretIdFromDevnet(id: string): ImportedSecret {
    return new ImportedSecret(
      id,
      { sourceProject: 'da-cn-devnet', secretId: id },
      { ...this.opts, dependsOn: [this.secretmanager] }
    );
  }

  constructor(name: string, args: GcpProjectArgs, opts?: pulumi.CustomResourceOptions) {
    super('cn:gcp:project', name, args, opts);

    this.opts = opts;

    const { gcpProjectId } = args;
    const keyringProjectId = config.requireEnv('PULUMI_BACKEND_GCPKMS_PROJECT');
    if (!keyringProjectId) {
      throw new Error('PULUMI_BACKEND_GCPKMS_PROJECT is undefined');
    }
    const keyringRegion = config.requireEnv('CLOUDSDK_COMPUTE_REGION');
    if (!keyringRegion) {
      throw new Error('CLOUDSDK_COMPUTE_REGION is undefined');
    }

    // Enable required services

    this.enableService('container');
    this.enableService('servicenetworking');
    this.secretmanager = this.enableService('secretmanager');

    // Configure a network path for Google Services (CloudSQL only at the time of writing)
    //  to access private networks within the project.

    const address = new gcp.compute.GlobalAddress(
      'google-managed-services-default',
      {
        addressType: 'INTERNAL',
        name: 'google-managed-services-default',
        purpose: 'VPC_PEERING',
        prefixLength: 20,
        network: `projects/${gcpProjectId}/global/networks/default`,
      },
      opts
    );

    new gcp.servicenetworking.Connection(
      'google-managed-services-default-connection',
      {
        network: `projects/${gcpProjectId}/global/networks/default`,
        service: 'servicenetworking.googleapis.com',
        reservedPeeringRanges: [address.name],
      },
      opts
    );

    // Source SV identities from a pre-existing project (i.e. devnet)
    // Note: this should be fine when ran against devnet itself...
    //  - But since we can automate this now, we might want to simply generate new SV secrets per project
    //  - We also want to move this to the infra stack so we can parameterize # of SVs
    // TODO(DACH-NY/canton-network-internal#435): generate new SV secrets per project
    this.importSecretIdFromDevnet('sv-id');
    this.importSecretIdFromDevnet('sv2-id');
    this.importSecretIdFromDevnet('sv3-id');
    this.importSecretIdFromDevnet('sv4-id');
    // Import CometBft keys from devnet
    for (let i = 1; i <= 16; i++) {
      this.importSecretIdFromDevnet(`sv${i}-cometbft-keys`);
    }
    // Import Observability grafana key secret from devnet
    this.importSecretIdFromDevnet('grafana-keys');

    // Manage IAM and permissions
    new GcpServiceAccount(
      'circleci',
      {
        accountId: 'circleci',
        displayName: 'Circle CI',
        description: 'Service account for Circle CI (managed by Pulumi)',
        roles: [
          'roles/cloudsql.admin',
          'roles/compute.viewer',
          'roles/container.serviceAgent',
          'roles/logging.privateLogViewer',
          'roles/storage.objectAdmin',
          'roles/viewer',
          {
            id: 'roles/secretmanager.secretAccessor',
            condition: {
              title: 'SV IDs',
              description: '(managed by Pulumi)',
              expression: `
          resource.name.endsWith("-id/versions/latest")
          `,
            },
          },
          {
            id: 'roles/secretmanager.secretAccessor',
            condition: {
              title: 'CometBft keys',
              description: '(managed by Pulumi)',
              expression: `
          resource.name.endsWith("-cometbft-keys/versions/latest")
          `,
            },
          },
          {
            id: 'roles/secretmanager.secretAccessor',
            condition: {
              title: 'Grafana keys',
              description: '(managed by Pulumi)',
              expression: `
          resource.name.endsWith("grafana-keys/versions/latest")
          `,
            },
          },
          {
            id: 'roles/secretmanager.secretAccessor',
            condition: {
              title: 'SA key secret',
              description: '(managed by Pulumi)',
              expression: `resource.name.endsWith("secrets/gcp-bucket-sa-key-secret/versions/1")`,
            },
          },
          {
            id: 'roles/cloudkms.cryptoKeyEncrypterDecrypter',
            condition: {
              title: 'Pulumi KMS',
              description: '(managed by Pulumi)',
              expression: `resource.type == "cloudkms.googleapis.com/CryptoKey" &&
            resource.name.startsWith("projects/'${keyringProjectId}'/locations/'${keyringRegion}'/keyRings/pulumi")`,
            },
          },
        ],
      },
      opts
    );
  }
}

interface GcpProjectArgs {
  gcpProjectId: string;
}

export { GcpProject };
