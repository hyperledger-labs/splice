// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import * as fs from 'fs';
import * as path from 'path';
import { setMocks } from '@pulumi/pulumi/runtime/mocks';
import { AsyncLocalStorage } from 'async_hooks';

import {
  Auth0ClientSecret,
  Auth0ClusterConfig,
  Auth0Config,
  Auth0NamespaceConfig,
  NamespacedAuth0Configs,
} from './auth0/auth0types';
import { isMainNet } from './config';
import { ClusterBasename } from './config/gcpConfig';

// Importing DEFAULT_AUDIENCE from auth0/audiences.ts creates a nightmare of things getting initialized too early, so we just redefine it here
const DEFAULT_AUDIENCE = 'https://canton.network.global';

export enum PulumiFunction {
  // tokens for functions being called during the test run,
  // these are of the form "package:module:function"
  GCP_GET_PROJECT = 'gcp:organizations/getProject:getProject',
  GCP_GET_SUB_NETWORK = 'gcp:compute/getSubnetwork:getSubnetwork',
  GCP_GET_SECRET_VERSION = 'gcp:secretmanager/getSecretVersion:getSecretVersion',
  GCP_GET_CLUSTER = 'gcp:container/getCluster:getCluster',
  STD_BASE64_DECODE = 'std:index:base64decode',
  GCP_GET_DATABASE_INSTANCES = 'gcp:sql/getDatabaseInstances:getDatabaseInstances',
}

export class SecretsFixtureMap extends Map<string, Auth0ClientSecret> {
  /* eslint-disable @typescript-eslint/no-explicit-any */
  override get(key: string): any {
    return { client_id: key, client_secret: '***' };
  }
}

const sv1Auth0Config: Auth0NamespaceConfig = {
  audiences: {
    ledgerApi: DEFAULT_AUDIENCE,
    svAppApi: DEFAULT_AUDIENCE,
    validatorApi: DEFAULT_AUDIENCE,
  },
  backendClientIds: {
    svApp: 'sv1-sv-client-id',
    validator: 'sv1-validator-client-id',
  },
  uiClientIds: {
    wallet: 'sv-1-wallet-ui-client-id',
    cns: 'sv-1-cns-ui-client-id',
    sv: 'sv-1-sv-ui-client-id',
  },
};

const svDa1Auth0Config: Auth0NamespaceConfig = {
  audiences: {
    ledgerApi: DEFAULT_AUDIENCE,
    svAppApi: DEFAULT_AUDIENCE,
    validatorApi: DEFAULT_AUDIENCE,
  },
  backendClientIds: {
    svApp: 'sv-da-1-sv-client-id',
    validator: 'sv-da-1-validator-client-id',
  },
  uiClientIds: {
    wallet: 'sv-da-1-wallet-ui-client-id',
    cns: 'sv-da-1-cns-ui-client-id',
    sv: 'sv-da-1-sv-ui-client-id',
  },
};

const sv2Auth0Config: Auth0NamespaceConfig = {
  audiences: {
    ledgerApi: DEFAULT_AUDIENCE,
    svAppApi: DEFAULT_AUDIENCE,
    validatorApi: DEFAULT_AUDIENCE,
  },
  backendClientIds: {
    svApp: 'sv2-sv-client-id',
    validator: 'sv2-validator-client-id',
  },
  uiClientIds: {
    wallet: 'sv-2-wallet-ui-client-id',
    cns: 'sv-2-cns-ui-client-id',
    sv: 'sv-2-sv-ui-client-id',
  },
};

const sv3Auth0Config: Auth0NamespaceConfig = {
  audiences: {
    ledgerApi: DEFAULT_AUDIENCE,
    svAppApi: DEFAULT_AUDIENCE,
    validatorApi: DEFAULT_AUDIENCE,
  },
  backendClientIds: {
    svApp: 'sv3-sv-client-id',
    validator: 'sv3-validator-client-id',
  },
  uiClientIds: {
    wallet: 'sv-3-wallet-ui-client-id',
    cns: 'sv-3-cns-ui-client-id',
    sv: 'sv-3-sv-ui-client-id',
  },
};

const sv4Auth0Config: Auth0NamespaceConfig = {
  audiences: {
    ledgerApi: DEFAULT_AUDIENCE,
    svAppApi: DEFAULT_AUDIENCE,
    validatorApi: DEFAULT_AUDIENCE,
  },
  backendClientIds: {
    svApp: 'sv4-sv-client-id',
    validator: 'sv4-validator-client-id',
  },
  uiClientIds: {
    wallet: 'sv-4-wallet-ui-client-id',
    cns: 'sv-4-cns-ui-client-id',
    sv: 'sv-4-sv-ui-client-id',
  },
};

const validator1Auth0Config: Auth0NamespaceConfig = {
  audiences: {
    ledgerApi: DEFAULT_AUDIENCE,
    validatorApi: DEFAULT_AUDIENCE,
  },
  backendClientIds: {
    validator: 'validator1-client-id',
  },
  uiClientIds: {
    wallet: 'validator1-wallet-ui-client-id',
    cns: 'validator1-cns-ui-client-id',
    splitwell: 'validator1-splitwell-ui-client-id',
    walletGateway: 'validator1-wallet-gateway-ui-client-id',
  },
};

const splitwellAuth0Config: Auth0NamespaceConfig = {
  audiences: {
    ledgerApi: DEFAULT_AUDIENCE,
    validatorApi: DEFAULT_AUDIENCE,
  },
  backendClientIds: {
    validator: 'splitwell-validator-client-id',
    splitwell: 'splitwell-client-id',
  },
  uiClientIds: {
    wallet: 'splitwell-wallet-ui-client-id',
    cns: 'splitwell-cns-ui-client-id',
    splitwell: 'splitwell-splitwell-ui-client-id',
  },
};

const namespacedConfigs: NamespacedAuth0Configs = {};
namespacedConfigs['sv-1'] = sv1Auth0Config;
namespacedConfigs['sv-da-1'] = svDa1Auth0Config;
namespacedConfigs['sv-2'] = sv2Auth0Config;
namespacedConfigs['sv-3'] = sv3Auth0Config;
namespacedConfigs['sv-4'] = sv4Auth0Config;
namespacedConfigs['validator1'] = validator1Auth0Config;
namespacedConfigs['splitwell'] = splitwellAuth0Config;

export const cantonNetworkAuth0Config: Auth0Config = {
  namespacedConfigs: namespacedConfigs,
  auth0Domain: isMainNet
    ? 'canton-network-mainnet.us.auth0.com'
    : 'canton-network-dev.us.auth0.com',
  auth0MgtClientId: 'auth0MgtClientId',
  auth0MgtClientSecret: 'auth0MgtClientSecret',
  fixedTokenCacheName: 'fixedTokenCacheName',
};

const svRunbookNamespacedConfigs: NamespacedAuth0Configs = {
  sv: {
    audiences: {
      ledgerApi: 'https://ledger_api.example.com', // The Ledger API in the sv-test tenant
      svAppApi: 'https://sv.example.com/api', // The SV App API in the sv-test tenant
      validatorApi: 'https://validator.example.com/api', // The Validator App API in the sv-test tenant
    },
    backendClientIds: {
      svApp: 'sv-client-id',
      validator: 'validator-client-id',
    },
    uiClientIds: {
      wallet: 'wallet-client-id',
      cns: 'cns-client-id',
      sv: 'sv-client-id',
    },
  },
};

export const svRunbookAuth0Config = {
  namespacedConfigs: svRunbookNamespacedConfigs,
  auth0Domain: 'canton-network-sv-test.us.auth0.com',
  auth0MgtClientId: 'auth0MgtClientId',
  auth0MgtClientSecret: 'auth0MgtClientSecret',
  fixedTokenCacheName: 'fixedTokenCacheName',
};

// Name of the stack (e.g. sv or validator) whose resources are currently being created.
// Projects that deploy one stack per sv/validator use it to write each stack's resources to its own folder.
const dumpConfigStack = new AsyncLocalStorage<string>();

export function withDumpConfigStack<T>(stack: string, f: () => T): T {
  return dumpConfigStack.run(stack, f);
}

function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(sortKeys);
  } else if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map(key => [key, sortKeys((value as Record<string, unknown>)[key])])
    );
  } else {
    return value;
  }
}

// Replace absolute paths to helm charts with relative paths so the output does not depend on the checkout location
function relativizeChartPaths(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(relativizeChartPaths);
  } else if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, v]) => [
        key,
        key === 'chart' && typeof v === 'string'
          ? v.replace(/^\/.*?(?=\/cluster\/helm\/)/, '')
          : relativizeChartPaths(v),
      ])
    );
  } else {
    return value;
  }
}

function sanitizeFileName(name: string): string {
  return name.replace(/[^A-Za-z0-9._-]+/g, '_');
}

// Every resource is written to its own file under DUMP_CONFIG_OUTPUT_DIR (in a subfolder per stack if set via withDumpConfigStack).
// Files are only written once the process exits successfully, so that resources sharing a file path can be given deterministic names.
function registerResourceFileWriter(): (args: pulumi.runtime.MockResourceArgs) => void {
  const outputDir = process.env.DUMP_CONFIG_OUTPUT_DIR;
  if (!outputDir) {
    throw new Error(
      'DUMP_CONFIG_OUTPUT_DIR must be set to the directory the resources are written to'
    );
  }
  const resources = new Map<string, Set<string>>();
  process.on('exit', code => {
    if (code !== 0) {
      return;
    }
    fs.rmSync(outputDir, { recursive: true, force: true });
    for (const [filePath, contents] of resources) {
      fs.mkdirSync(path.dirname(filePath), { recursive: true });
      const sorted = [...contents].sort();
      sorted.forEach((content, index) => {
        const target = index === 0 ? filePath : filePath.replace(/\.json$/, `.${index}.json`);
        fs.writeFileSync(target, content);
      });
    }
  });
  return args => {
    const stack = dumpConfigStack.getStore();
    const fileName = `${sanitizeFileName(args.name)}.${sanitizeFileName(args.type)}.json`;
    const filePath = path.resolve(outputDir, ...(stack ? [sanitizeFileName(stack)] : []), fileName);
    const content = JSON.stringify(sortKeys(relativizeChartPaths(args)), undefined, 2) + '\n';
    const contents = resources.get(filePath) ?? new Set<string>();
    contents.add(content);
    resources.set(filePath, contents);
  };
}

/*eslint no-process-env: "off"*/
export async function initDumpConfig({
  stackOutputsProvider = infraStackOutputsProvider,
}: MockSettings = {}): Promise<void> {
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

  const projectName = 'test-project';
  const stackName = 'test-stack';
  const writeResource = registerResourceFileWriter();

  await setMocks(
    {
      newResource: function (args: pulumi.runtime.MockResourceArgs): {
        id: string;
        state: any;
      } {
        writeResource(args);

        switch (args.type) {
          case 'pulumi:pulumi:StackReference': {
            const [organization, project, stack] = args.name.split('/');
            return {
              id: args.name + '_id',
              state: {
                ...args.inputs,
                outputs: pulumi.output(stackOutputsProvider(project, stack) ?? {}),
              },
            };
          }
          default:
            return {
              id: args.id ?? args.inputs.name + '_id',
              state: args.inputs,
            };
        }
      },
      call: function (args: pulumi.runtime.MockCallArgs) {
        switch (args.token) {
          case PulumiFunction.STD_BASE64_DECODE:
            return {
              result: `base64-decoded-mock`,
            };
          case PulumiFunction.GCP_GET_PROJECT:
            return { ...args.inputs, name: projectName, projectId: projectName };
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
            } else if (args.inputs.secret == 'gcp-topology-snapshot-bucket-sa-key-secret') {
              const secretData = JSON.stringify({
                projectId: args.inputs.project,
                bucketName: 'topology-snapshot-bucket-name',
                secretName: 'gcp-topology-snapshot-bucket-sa-key-secret',
                jsonCredentials: 'topology-snapshot-bucket-sa-key-secret-creds',
                bucketSaKeySecret: 'gcp-topology-snapshot-bucket-sa-key-example',
                bucketSaIamAccount: 'da-cn-examplet@da-cn-shared.iam.gserviceaccount.com',
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
          case PulumiFunction.GCP_GET_DATABASE_INSTANCES:
            return {
              instances: [
                {
                  name: 'sv-1-cn-apps-pg-7ca4614',
                  settings: [{ userLabels: { cluster: ClusterBasename } }],
                },
              ],
            };
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

export type MockSettings = {
  stackOutputsProvider?: StackOutputsProvider;
};

export type StackOutputsProvider = (
  project: string,
  stack: string
) => Partial<Record<string, any>> | undefined;

export const infraStackOutputsProvider: StackOutputsProvider = (project: string) => {
  switch (project) {
    case 'canton-network':
      return {
        svs: [...Array.from({ length: 16 }, (_, index) => `sv-${index + 1}`), 'sv-da-1'].map(
          nodeName => ({
            nodeName,
            databaseInstanceName: `${nodeName}-cn-apps-pg`,
            databaseSecretName: `${nodeName}-cn-apps-pg-secret`,
          })
        ),
      };
    case 'infra':
      return {
        istioDashboardVersions: '1234',
        auth0: {
          svRunbook: svRunbookAuth0Config,
          cantonNetwork: cantonNetworkAuth0Config,
          mainnet: cantonNetworkAuth0Config,
        } as Auth0ClusterConfig,
      };
    default:
      return undefined;
  }
};
