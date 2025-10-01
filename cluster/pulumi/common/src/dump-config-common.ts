// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import * as path from 'path';
import * as sinon from 'sinon';
import { setMocks } from '@pulumi/pulumi/runtime/mocks';

import { Auth0ClientSecret, Auth0ClusterConfig } from './auth0types';
import { isMainNet } from './config';

export enum PulumiFunction {
  // tokens for functions being called during the test run,
  // these are of the form "package:module:function"
  GCP_GET_PROJECT = 'gcp:organizations/getProject:getProject',
  GCP_GET_SUB_NETWORK = 'gcp:compute/getSubnetwork:getSubnetwork',
  GCP_GET_SECRET_VERSION = 'gcp:secretmanager/getSecretVersion:getSecretVersion',
  GCP_GET_CLUSTER = 'gcp:container/getCluster:getCluster',
}

export class SecretsFixtureMap extends Map<string, Auth0ClientSecret> {
  /* eslint-disable @typescript-eslint/no-explicit-any */
  override get(key: string): any {
    return { client_id: key, client_secret: '***' };
  }
}

export const cantonNetworkAuth0Config = {
  appToClientId: {
    validator1: 'validator1-client-id',
    splitwell: 'splitwell-client-id',
    splitwell_validator: 'splitwell-validator-client-id',
    'sv-1': 'sv-1-client-id',
    'sv-2': 'sv-2-client-id',
    'sv-3': 'sv-3-client-id',
    'sv-4': 'sv-4-client-id',
    sv1_validator: 'sv1-validator-client-id',
    sv2_validator: 'sv2-validator-client-id',
    sv3_validator: 'sv3-validator-client-id',
    sv4_validator: 'sv4-validator-client-id',
    sv: 'sv-client-id',
    validator: 'sv-client-id',
    'sv-da-1': 'sv-da-1-client-id',
    'sv-da-1_validator': 'sv-da-1-validator-client-id',
  },
  namespaceToUiToClientId: {
    validator1: {
      wallet: 'validator1-wallet-ui-client-id',
      cns: 'validator1-cns-ui-client-id',
      splitwell: 'validator1-splitwell-ui-client-id',
    },
    splitwell: {
      wallet: 'splitwell-wallet-ui-client-id',
      cns: 'splitwell-cns-ui-client-id',
      splitwell: 'splitwell-splitwell-ui-client-id',
    },
    'sv-1': {
      wallet: 'sv-1-wallet-ui-client-id',
      cns: 'sv-1-cns-ui-client-id',
      sv: 'sv-1-sv-ui-client-id',
    },
    'sv-2': {
      wallet: 'sv-2-wallet-ui-client-id',
      cns: 'sv-2-cns-ui-client-id',
      sv: 'sv-2-sv-ui-client-id',
    },
    'sv-3': {
      wallet: 'sv-3-wallet-ui-client-id',
      cns: 'sv-3-cns-ui-client-id',
      sv: 'sv-3-sv-ui-client-id',
    },
    'sv-4': {
      wallet: 'sv-4-wallet-ui-client-id',
      cns: 'sv-4-cns-ui-client-id',
      sv: 'sv-4-sv-ui-client-id',
    },
    'sv-da-1': {
      wallet: 'sv-da-1-wallet-ui-client-id',
      cns: 'sv-da-1-cns-ui-client-id',
      sv: 'sv-da-1-sv-ui-client-id',
    },
  },
  appToApiAudience: {},
  appToClientAudience: {},
  auth0Domain: isMainNet
    ? 'canton-network-mainnet.us.auth0.com'
    : 'canton-network-dev.us.auth0.com',
  auth0MgtClientId: 'auth0MgtClientId',
  auth0MgtClientSecret: 'auth0MgtClientSecret',
  fixedTokenCacheName: 'fixedTokenCacheName',
};
export const svRunbookAuth0Config = {
  appToClientId: {
    sv: 'sv-client-id',
    validator: 'validator-client-id',
  },
  namespaceToUiToClientId: {
    sv: {
      wallet: 'wallet-client-id',
      cns: 'cns-client-id',
      sv: 'sv-client-id',
    },
  },
  appToApiAudience: {
    participant: 'https://ledger_api.example.com', // The Ledger API in the sv-test tenant
    sv: 'https://sv.example.com/api', // The SV App API in the sv-test tenant
    validator: 'https://validator.example.com/api', // The Validator App API in the sv-test tenant
  },

  appToClientAudience: {
    sv: 'https://ledger_api.example.com',
    validator: 'https://ledger_api.example.com',
  },
  auth0Domain: 'canton-network-sv-test.us.auth0.com',
  auth0MgtClientId: 'auth0MgtClientId',
  auth0MgtClientSecret: 'auth0MgtClientSecret',
  fixedTokenCacheName: 'fixedTokenCacheName',
};

/*eslint no-process-env: "off"*/
export async function initDumpConfig(): Promise<void> {
  // DO NOT ADD NON SECRET VALUES HERE, ALL THE VALUES SHOULD BE DEFINED BY THE CLUSTER ENVIRONMENT in .envrc.vars
  // THIS IS REQUIRED TO ENSURE THAT THE DEPLOYMENT OPERATOR HAS THE SAME ENV AS A LOCAL RUN
  process.env.AUTH0_CN_MANAGEMENT_API_CLIENT_ID = 'mgmt';
  process.env.AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET = 's3cr3t';
  process.env.AUTH0_SV_MANAGEMENT_API_CLIENT_ID = 'mgmt';
  process.env.AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET = 's3cr3t';
  process.env.AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_ID = 'mgmt';
  process.env.AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_SECRET = 's3cr3t';
  process.env.AUTH0_MAIN_MANAGEMENT_API_CLIENT_ID = 'mgmt';
  process.env.AUTH0_MAIN_MANAGEMENT_API_CLIENT_SECRET = 's3cr3t';
  process.env.MOCK_SPLICE_ROOT = 'SPLICE_ROOT';
  process.env.PULUMI_VERSION = '0.0.0';
  // the project name in setMocks seems to be ignored and we need to load the proper config, so we override it here to ensure we  always use the same config as in prod
  process.env.CONFIG_PROJECT_NAME = path.basename(process.cwd());
  // StackReferences cannot be mocked in tests currently
  // (see https://github.com/pulumi/pulumi/issues/9212)
  sinon
    .stub(pulumi.StackReference.prototype, 'requireOutput')
    .callsFake((name: pulumi.Input<string>) => {
      switch (name.valueOf()) {
        case 'ingressNs':
          return pulumi.output('cn-namespace');
        case 'ingressIp':
          return pulumi.output('127.0.0.2');
        case 'auth0':
          return pulumi.output({
            svRunbook: svRunbookAuth0Config,
            cantonNetwork: cantonNetworkAuth0Config,
            mainnet: cantonNetworkAuth0Config,
          } as Auth0ClusterConfig);
        default:
          throw new Error(`unknown name for requireOutput(): ${name}`);
      }
    });

  const projectName = 'test-project';
  const stackName = 'test-stack';

  await setMocks(
    {
      newResource: function (args: pulumi.runtime.MockResourceArgs): {
        id: string;
        state: any; // eslint-disable-line @typescript-eslint/no-explicit-any
      } {
        const buffer = Buffer.from(JSON.stringify(args, undefined, 4), 'utf8');
        process.stdout.write(buffer);
        process.stdout.write('\n');

        return {
          id: args.inputs.name + '_id',
          state: args.inputs,
        };
      },
      call: function (args: pulumi.runtime.MockCallArgs) {
        switch (args.token) {
          case PulumiFunction.GCP_GET_PROJECT:
            return { ...args.inputs, name: projectName };
          case PulumiFunction.GCP_GET_SUB_NETWORK:
            if (args.inputs.name === `cn-${stackName}net-subnet`) {
              return { ...args.inputs, id: 'subnet-id' };
            } else {
              console.error(
                `WARN sub-network not supported for mocking in setMockOptions: ${args.inputs.name}`
              );
              break;
            }
          case PulumiFunction.GCP_GET_CLUSTER:
            return {
              nodePools: [{ networkConfigs: [{ podIpv4CidrBlock: '10.160.0.0/16' }] }],
            };
          case PulumiFunction.GCP_GET_SECRET_VERSION:
            if (args.inputs.secret.startsWith('sv') && args.inputs.secret.endsWith('-id')) {
              return {
                ...args.inputs,
                secretData: `{"publicKey": "${args.inputs.secret}-public-key", "privateKey": "${args.inputs.secret}-private-key"}`,
              };
            } else if (
              args.inputs.secret.startsWith('sv') &&
              args.inputs.secret.endsWith('-keys')
            ) {
              return {
                ...args.inputs,
                secretData: `{"nodePrivateKey": "${args.inputs.secret}-node-private-key", "validatorPrivateKey": "${args.inputs.secret}-validator-private-key"
                , "validatorPublicKey": "${args.inputs.secret}-validator-public-key"}`,
              };
            } else if (
              args.inputs.secret.startsWith('sv') &&
              args.inputs.secret.endsWith('-governance-key')
            ) {
              return {
                ...args.inputs,
                secretData: `{"public": "${args.inputs.secret}-public-key", "private": "${args.inputs.secret}-private-key"}`,
              };
            } else if (args.inputs.secret.startsWith('grafana-keys')) {
              return {
                ...args.inputs,
                secretData: `{"adminUser": "${args.inputs.secret}-admin-user"
                , "adminPassword": "${args.inputs.secret}-admin-password"}`,
              };
            } else if (args.inputs.secret == 'gcp-bucket-sa-key-secret') {
              const secretData = JSON.stringify({
                projectId: args.inputs.project,
                bucketName: 'data-export-bucket-name',
                secretName: 'data-export-bucket-sa-key-secret',
                jsonCredentials: 'data-export-bucket-sa-key-secret-creds',
              });
              return {
                ...args.inputs,
                secretData,
              };
            } else if (args.inputs.secret == 'artifactory-keys') {
              const secretData = JSON.stringify({
                username: 'art_user',
                password: 's3cr3t',
              });
              return {
                ...args.inputs,
                secretData,
              };
            } else if (args.inputs.secret == 'us-central1-artifact-reader-key') {
              const secretData = JSON.stringify({
                type: 'service_account',
                project_id: 'fake-project',
                private_key_id: 'fake_id',
                private_key: '-----BEGIN PRIVATE KEY-----\nfake\n-----END PRIVATE KEY-----\n',
                client_email: 'fake@fake-project.iam.gserviceaccount.com',
                client_id: 'fake-client-id',
                auth_uri: 'https://accounts.google.com/o/oauth2/auth',
                token_uri: 'https://oauth2.googleapis.com/token',
                auth_provider_x509_cert_url: 'https://www.googleapis.com/oauth2/v1/certs',
                client_x509_cert_url:
                  'https://www.googleapis.com/robot/v1/metadata/x509/fake%40fake-project.iam.gserviceaccount.com',
                universe_domain: 'googleapis.com',
              });
              return {
                ...args.inputs,
                secretData,
              };
            } else if (args.inputs.secret == 'pulumi-internal-whitelists') {
              return {
                ...args.inputs,
                secretData: '["<internal IPs>"]',
              };
            } else if (args.inputs.secret.startsWith('pulumi-user-configs-')) {
              const secretData = JSON.stringify([
                {
                  user_id: 'google-oauth2|1234567890',
                  email: 'someone@test.com',
                },
              ]);
              return {
                ...args.inputs,
                secretData,
              };
            } else if (args.inputs.secret == 'pulumi-lets-encrypt-email') {
              return {
                ...args.inputs,
                secretData: 'email-for-letsencrypt@test.com',
              };
            } else {
              console.error(
                `WARN gcp secret not supported for mocking in setMockOptions: ${args.inputs.secret}`
              );
              break;
            }
          default:
            console.error('WARN unhandled call in setMockOptions: ', args);
        }
        return args.inputs;
      },
    },
    projectName,
    stackName
  );
}
