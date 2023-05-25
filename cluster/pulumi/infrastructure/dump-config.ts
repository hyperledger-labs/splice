import * as pulumi from '@pulumi/pulumi';

// TODO(#5036) Reduce code duplication throughout Pulumi projects
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
        case PulumiFunction.GCP_GET_SUB_NETWORK:
          if (args.inputs.name === `cn-${stackName}net-subnet`) {
            return { ...args.inputs, id: 'subnet-id' };
          }
      }
      console.error('WARN unhandled call: ', args);
      return args.inputs;
    },
  },
  projectName,
  stackName
);

async function main() {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const infra: typeof import('./src/index') = await import('./src/index');
}

main();
