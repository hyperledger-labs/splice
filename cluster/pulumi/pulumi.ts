import * as automation from '@pulumi/pulumi/automation';
import fs from 'fs';
import util from 'node:util';
import os from 'os';
import path from 'path';
import { config } from 'splice-pulumi-common/src/config';
import { CLUSTER_BASENAME, REPO_ROOT } from 'splice-pulumi-common/src/utils';

const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pulumi-'));

/**
 * This is literally dumb:
 * - Selecting the stack through the workspace will for some reason create a `workspaces` directory in the pulumi home direcotry
 *    - There`s no way to disable this behavior
 *    - There's no way to skip selecting the stack using the automation api
 * - The default pulumi home directory is in nix for us so it's read only
 * - Changing the pulumi home for the preview would cause all the plguins to be reinstalled
 * - The only way to get around this is to symlink the plugins and bin directories to a temporary directory
 * */
const pulumiHome = config.requireEnv('PULUMI_HOME');
const binDir = path.join(pulumiHome, 'bin');
const pluginsDir = path.join(pulumiHome, 'plugins');
const tempBinDir = path.join(tempDir, 'bin');
const tempPluginsDir = path.join(tempDir, 'plugins');

fs.symlinkSync(binDir, tempBinDir, 'dir');
fs.symlinkSync(pluginsDir, tempPluginsDir, 'dir');
const commandPromise = automation.PulumiCommand.get({
  // eslint-disable-next-line no-process-env
  root: process.env.PULUMI_HOME,
  // enforce typescript sdk version to match the pulumi cli version
  skipVersionCheck: false,
});

export function pulumiOptsWithPrefix(prefix: string, abortSignal: AbortSignal): {
  parallel: 128;
  onOutput: (output: string) => void;
  signal: AbortSignal;
} {
  return {
    parallel: 128,
    onOutput: (output: string) => {
      // do not output empty lines or lines containing just '.'
      if (output.trim().length > 1) {
        console.log(`${prefix}${output.trim()}`);
      }
    },
    signal: abortSignal,
  };
}

export async function stack(
  project: string,
  stackName: string,
  requiresExistingStack: boolean,
  envVars: {
    [key: string]: string;
  }
): Promise<automation.Stack> {
  const fullStackName = `organization/${project}/${stackName}.${CLUSTER_BASENAME}`;
  const command = await commandPromise;
  // safe to use process.env as we check if we're in a CI env
  // eslint-disable-next-line no-process-env
  const stackMustAlreadyExist = process.env.CI !== undefined && requiresExistingStack;
  const projectDirectory = `${REPO_ROOT}/cluster/pulumi/${project}`;
  const stackOpts: automation.LocalProgramArgs = {
    workDir: projectDirectory,
    stackName: fullStackName,
  };
  const workspaceOpts: automation.LocalWorkspaceOptions = {
    secretsProvider: `gcpkms://projects/${config.requireEnv('PULUMI_BACKEND_GCPKMS_PROJECT')}/locations/${config.requireEnv('CLOUDSDK_COMPUTE_REGION')}/keyRings/pulumi/cryptoKeys/${config.requireEnv('PULUMI_BACKEND_GCPKMS_NAME')}`,
    envVars: envVars,
    workDir: projectDirectory,
    pulumiCommand: command,
    pulumiHome: tempDir,
  };

  return stackMustAlreadyExist
    ? await automation.LocalWorkspace.selectStack(stackOpts, workspaceOpts)
    : await automation.LocalWorkspace.createOrSelectStack(stackOpts, workspaceOpts);
}

export async function refreshStack(stack: automation.Stack, abortSignal: AbortSignal): Promise<void> {
  const name = stack.name;
  console.log(`${name} - Refreshing stack`);
  await stack.refresh(pulumiOptsWithPrefix(`[${name}]`, abortSignal));
}

export async function downStack(stack: automation.Stack, abortSignal: AbortSignal): Promise<void> {
  const name = stack.name;
  console.log(`${name} - Destroying stack`);
  await stack.destroy(pulumiOptsWithPrefix(`[${name}]`, abortSignal));
}

export async function upStack(stack: automation.Stack, abortSignal: AbortSignal): Promise<void> {
  const name = stack.name;
  const result = await stack.up(pulumiOptsWithPrefix(`[${name}]`, abortSignal));
  console.log(`${name} stack up result:`);
  console.log(util.inspect(result.summary, { colors: true, depth: null, maxStringLength: null }));
}
