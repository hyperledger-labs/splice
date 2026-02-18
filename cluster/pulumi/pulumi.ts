// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as automation from '@pulumi/pulumi/automation';
import { config } from '@lfdecentralizedtrust/splice-pulumi-common/src/config';
import {
  CLUSTER_BASENAME,
  PULUMI_STACKS_DIR,
} from '@lfdecentralizedtrust/splice-pulumi-common/src/utils';
import fs from 'fs';
import os from 'os';
import path from 'path';
import {spliceEnvConfig} from "@lfdecentralizedtrust/splice-pulumi-common/src/config/envConfig";
import {allowDowngrade} from "@lfdecentralizedtrust/splice-pulumi-common";

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

export function pulumiOptsWithPrefix(
  prefix: string,
  abortSignal: AbortSignal
): {
  parallel: 128;
  onOutput: (output: string) => void;
  signal: AbortSignal;
  color: 'always';
  policyPacks: string[];
  diff: boolean;
} {
  return {
    diff: true,
    parallel: 128,
    onOutput: (output: string) => {
      // do not output empty lines or lines containing just '.'
      if (output.trim().length > 1) {
        console.log(`${prefix}${output.trim()}`);
      }
    },
    signal: abortSignal,
    color: 'always',
    policyPacks: allowDowngrade ? [] : [`${spliceEnvConfig.context.splicePath}/cluster/pulumi/policies`],
  };
}

function getSecretsProvider() {
  return `gcpkms://projects/${config.requireEnv(
    'PULUMI_BACKEND_GCPKMS_PROJECT'
  )}/locations/${config.requireEnv(
    'CLOUDSDK_COMPUTE_REGION'
  )}/keyRings/pulumi/cryptoKeys/${config.requireEnv('PULUMI_BACKEND_GCPKMS_NAME')}`;
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
  const projectDirectory = `${PULUMI_STACKS_DIR}/${project}`;
  const stackOpts: automation.LocalProgramArgs = {
    workDir: projectDirectory,
    stackName: fullStackName,
  };
  const workspaceOpts: automation.LocalWorkspaceOptions = {
    secretsProvider: getSecretsProvider(),
    envVars: envVars,
    workDir: projectDirectory,
    pulumiCommand: command,
    pulumiHome: tempDir,
  };

  return requiresExistingStack
    ? await automation.LocalWorkspace.selectStack(stackOpts, workspaceOpts)
    : await automation.LocalWorkspace.createOrSelectStack(stackOpts, workspaceOpts);
}

export async function ensureStackSettingsAreUpToDate(stack: automation.Stack): Promise<void> {
  // This nice API ensures that the local stack file is updated with the latest settings stored in the actual state file
  // if not done, pulumi automation will sometimes complain that the secrets passphrase is not set
  const settings = await stack.workspace.stackSettings(stack.name);
  await stack.workspace.saveStackSettings(stack.name, {
    ...settings,
    secretsProvider: getSecretsProvider(),
  });
}

// An AbortController that:
// 1. Also listens for SIGINT and SIGTERM signals
// 2. Guarantees it will signal only once because aborting pulumi is not idempotent, if we signal twice
//    pulumi will abort without cleanup.
// 3. Waits a few seconds before actually signalling, see https://github.com/DACH-NY/canton-network-node/issues/15519
//    for the reason (the gist is: aborting pulumi actions too early causes pulumi to terminate without releasing the lock)
export class PulumiAbortController {
  constructor() {
    ['SIGINT', 'SIGTERM'].forEach(signal =>
      // We assume here that an external abort signal will not come immediately, and do not
      // wait before sending the actual signal to Pulumi. This is because we do not want to
      // add delays to cleaning up when CCI terminates us, to try to avoid CCI timing out and
      // hard-killing us.
      process.on(signal, () => {
        this.abort('Aborting due to caught signal');
      })
    );
  }

  private controller = new AbortController();
  private aborted = false;
  private sentAbort = false;

  private WAIT_BEFORE_ABORT = 10000;

  public abort(reason?: unknown): void {
    if (!this.aborted) {
      console.error(`Aborting after the wait time: ${reason}`);
      const c = this.controller;
      setTimeout(
        () => {
          console.error(`Aborting: ${reason}`);
          if (!this.sentAbort) {
            this.sentAbort = true;
            c.abort(reason);
          }
        },
        // some randomness to prevent double execution
        Math.random() * 1000 + this.WAIT_BEFORE_ABORT
      );
    }
    this.aborted = true;
  }

  public get signal(): AbortSignal {
    return this.controller.signal;
  }
}

export interface Operation {
  name: string;
  promise: Promise<void>;
}

export async function awaitAllOrThrowAllExceptions(operations: Operation[]): Promise<void> {
  const data = await Promise.allSettled(
    operations.map(op => {
      console.error(`Running operation ${op.name}`);
      return op.promise.then(
        () => console.error(`Operation ${op.name} succeeded.`),
        err => {
          if (err instanceof automation.CommandError) {
            console.error(`Operation ${op.name} failed.`);
          } else {
            console.error(`Operation ${op.name} failed with an unknown error.`);
          }
          throw err;
        }
      );
    })
  );
  const rejectionReasons = (
    data.filter(res => res.status === 'rejected') as PromiseRejectedResult[]
  ).map(res => res.reason);
  if (rejectionReasons.length > 0) {
    const message = `Ran ${operations.length} operations. ${rejectionReasons.length} failed. Reasons of rejections: ${rejectionReasons}`;
    console.error(message);
    throw new Error(message);
  }
}
