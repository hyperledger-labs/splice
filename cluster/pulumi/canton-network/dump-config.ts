import * as pulumi from '@pulumi/pulumi';
import * as sinon from 'sinon';
import type { Auth0ClientSecret } from 'cn-pulumi-common';

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

// TODO(#4584) Reduce code duplication throughout Pulumi projects
export enum PulumiFunction {
  // tokens for functions being called during the test run,
  // these are of the form "package:module:function"
  GCP_GET_PROJECT = 'gcp:organizations/getProject:getProject',
  GCP_GET_SUB_NETWORK = 'gcp:compute/getSubnetwork:getSubnetwork',
}

const projectName = 'test-project';
const stackName = 'test-stack';

pulumi.runtime.setMocks(
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
            console.error(`WARN unhandled sub-network: ${args.inputs.name}`);
            break;
          }
        default:
          console.error('WARN unhandled call: ', args);
      }
      return args.inputs;
    },
  },
  projectName,
  stackName
);

class SecretsFixtureMap extends Map<string, Auth0ClientSecret> {
  override get(key: string) {
    return { client_id: key, client_secret: '***' };
  }
}

async function main() {
  pulumi.runtime.setConfig('test-project:CLUSTER_BASENAME', 'mock');
  pulumi.runtime.setConfig('test-project:FIXED_TOKENS', '0');
  pulumi.runtime.setConfig('test-project:X_NODES', '0');
  pulumi.runtime.setConfig('test-project:VERSION_NUMBER', '0.0.1');
  pulumi.runtime.setConfig('test-project:IMAGE_TAG', '0.0.1-deadbeef');

  process.env.AUTH0_MANAGEMENT_API_CLIENT_ID = 'mgmt';
  process.env.AUTH0_MANAGEMENT_API_CLIENT_SECRET = 's3cr3t';

  const installCluster = await import('./src/installCluster');
  const secrets = new SecretsFixtureMap();

  installCluster.installCluster({
    getSecrets: () => Promise.resolve(secrets),
    /* eslint-disable @typescript-eslint/no-unused-vars */
    getClientAccessToken: (clientId: string, clientSecret: string) =>
      Promise.resolve('access_token'),
  });
}

main();
