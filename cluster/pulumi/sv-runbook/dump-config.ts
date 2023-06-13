import * as pulumi from '@pulumi/pulumi';
import * as sinon from 'sinon';

// TODO(#4584) Reduce code duplication throughout Pulumi projects
export enum PulumiFunction {
  // tokens for functions being called during the test run,
  // these are of the form "package:module:function"
  GCP_GET_PROJECT = 'gcp:organizations/getProject:getProject',
  GCP_GET_SUB_NETWORK = 'gcp:compute/getSubnetwork:getSubnetwork',
}

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

async function main() {
  process.env.GCP_CLUSTER_BASENAME = 'svrun';
  process.env.AUTH0_DOMAIN = 'auth0.tenant.domain';
  process.env.TARGET_CLUSTER = 'svrun.network.com';
  process.env.ARTIFACTORY_USER = 'artie';
  process.env.ARTIFACTORY_PASSWORD = 's3cr3t';

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const infra: typeof import('./src/index') = await import('./src/index');
}

main();
