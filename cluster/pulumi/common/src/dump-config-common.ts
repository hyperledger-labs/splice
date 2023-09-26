import * as pulumi from '@pulumi/pulumi';
import * as sinon from 'sinon';
import { MockMonitor } from '@pulumi/pulumi/runtime/mocks';

import { Auth0ClientSecret } from './auth0types';

export enum PulumiFunction {
  // tokens for functions being called during the test run,
  // these are of the form "package:module:function"
  GCP_GET_PROJECT = 'gcp:organizations/getProject:getProject',
  GCP_GET_SUB_NETWORK = 'gcp:compute/getSubnetwork:getSubnetwork',
  GCP_GET_SECRET_VERSION = 'gcp:secretmanager/getSecretVersion:getSecretVersion',
}

export class SecretsFixtureMap extends Map<string, Auth0ClientSecret> {
  /* eslint-disable @typescript-eslint/no-explicit-any */
  override get(key: string): any {
    return { client_id: key, client_secret: '***' };
  }
}

export function initDumpConfig(): void {
  pulumi.runtime.setConfig('test-project:CLUSTER_BASENAME', 'mock');
  pulumi.runtime.setConfig('test-project:DNS01_SA_KEY_JSON', 'mock-json');
  pulumi.runtime.setConfig('test-project:DATA_EXPORT_BUCKET_SA_KEY_JSON', 'mock-json');
  pulumi.runtime.setConfig('test-project:FIXED_TOKENS', '0');
  pulumi.runtime.setConfig('test-project:VERSION_NUMBER', '0.0.1');
  pulumi.runtime.setConfig('test-project:IMAGE_TAG', '0.0.1-deadbeef');

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
        default:
          throw new Error(`unknown name for requireOutput(): ${name}`);
      }
    });

  const projectName = 'test-project';
  const stackName = 'test-stack';

  class MockMonitorWithFeatures extends MockMonitor {
    public supportsFeature(req: any, callback: (err: any, innerResponse: any) => void): void {
      const id = req.getId();

      // Support for "outputValues" is deliberately disabled for the mock monitor so
      // instances of `Output` don't show up in `MockResourceArgs` inputs.
      const hasSupport = 'outputValues' !== id;

      callback(null, {
        getHassupport: () => hasSupport,
      });
    }
  }

  pulumi.runtime.setMockOptions(
    new MockMonitorWithFeatures({
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
          case PulumiFunction.GCP_GET_SECRET_VERSION:
            if (args.inputs.secret.startsWith('sv') && args.inputs.secret.endsWith('-id')) {
              return {
                ...args.inputs,
                secretData: `{"publicKey": "${args.inputs.secret}-public-key", "privateKey": "${args.inputs.secret}-private-key"}`,
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
    }),
    projectName,
    stackName
  );
}
