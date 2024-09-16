import * as automation from '@pulumi/pulumi/automation';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { config } from 'splice-pulumi-common/src/config';
// We have to be explicit with the imports here, if we import a module that creates a pulumi resource running the preview will fail
// as we have no pulumi runtime
import {
  DecentralizedSynchronizerMigrationConfig,
  DomainMigrationIndex,
  externalMigrations,
  MigrationInfo,
} from 'splice-pulumi-common/src/domainMigration';
import { CLUSTER_BASENAME, DeploySvRunbook } from 'splice-pulumi-common/src/utils';

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

export const pulumiOpts: { parallel: number; onOutput: (output: string) => void } = {
  parallel: 128,
  onOutput: (output: string) => {
    console.log(output);
  },
};

export async function stackForMigration(
  nodeName: string,
  migrationId: DomainMigrationIndex,
  requiresExistingStack: boolean
): Promise<automation.Stack> {
  const stackName = `organization/sv-canton/sv-canton.${nodeName}-migration-${migrationId}.${CLUSTER_BASENAME}`;
  const command = await commandPromise;
  // safe to use process.env as we check if we're in a CI env
  // eslint-disable-next-line no-process-env
  const stackMustAlreadyExist = process.env.CI !== undefined && requiresExistingStack;
  const stackOpts: automation.LocalProgramArgs = {
    workDir: __dirname,
    stackName: stackName,
  };
  const workspaceOpts: automation.LocalWorkspaceOptions = {
    secretsProvider: `gcpkms://projects/${config.requireEnv('PULUMI_BACKEND_GCPKMS_PROJECT')}/locations/${config.requireEnv('CLOUDSDK_COMPUTE_REGION')}/keyRings/pulumi/cryptoKeys/${config.requireEnv('PULUMI_BACKEND_GCPKMS_NAME')}`,
    envVars: {
      SPLICE_MIGRATION_ID: migrationId.toString(),
      SPLICE_SV: nodeName,
    },
    workDir: __dirname,
    pulumiCommand: command,
    pulumiHome: tempDir,
  };

  return stackMustAlreadyExist
    ? await automation.LocalWorkspace.selectStack(stackOpts, workspaceOpts)
    : await automation.LocalWorkspace.createOrSelectStack(stackOpts, workspaceOpts);
}

const onlyRunbook = config.envFlag('SPLICE_DEPLOY_ONLY_SV_RUNBOOK');
const dsoSize = parseInt(config.requireEnv('DSO_SIZE'));
const migrations = externalMigrations(DecentralizedSynchronizerMigrationConfig.fromEnv());
const coreSvs = onlyRunbook ? [] : Array.from({ length: dsoSize }, (_, index) => `sv-${index + 1}`);
export const svsToDeploy = coreSvs.concat(DeploySvRunbook ? ['sv'] : []);

export async function runForAllMigrations(
  runForStack: (stack: automation.Stack, migration: MigrationInfo, sv: string) => Promise<void>,
  requiresExistingStack: boolean
): Promise<void> {
  console.log(
    `Running for migration ${JSON.stringify(migrations)} and svs ${JSON.stringify(svsToDeploy)}`
  );
  for (const migration of migrations.externalMigrations) {
    console.log(`Running for migration ${migration.migrationId}`);

    await Promise.all(
      svsToDeploy.map(async sv => {
        const stack = await stackForMigration(sv, migration.migrationId, requiresExistingStack);
        await runForStack(stack, migration, sv);
      })
    );
  }
}
