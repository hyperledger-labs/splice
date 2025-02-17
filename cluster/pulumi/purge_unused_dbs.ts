import * as automation from '@pulumi/pulumi/automation';
import * as gcp from '@pulumi/gcp';
import * as cloudsql from '@google-cloud/sql';
import { stack } from './pulumi';
import { runForAllMigrations } from './sv-canton/pulumi';
import { CLUSTER_BASENAME, config } from 'splice-pulumi-common';
import * as readline from 'readline';
import { program } from 'commander';

const gcpSqlClient = new cloudsql.SqlInstancesServiceClient({
  fallback: 'rest',
});

async function getDBsInStack(stack: automation.Stack): Promise<gcp.sql.DatabaseInstance[]> {
  const exported = await stack.exportStack();
  const resources = exported.deployment.resources;
  if (!resources) {
    return Promise.resolve([])
  }
  const dbs = resources.filter((r: any) => r.type === 'gcp:sql/databaseInstance:DatabaseInstance');
  return dbs;
}

async function getAllPulumiDbs(): Promise<gcp.sql.DatabaseInstance[]> {
  const projects = ['canton-network', 'sv-runbook', 'splitwell', 'validator1'];
  const coreDbs = await Promise.all(projects.map(async project =>
    await getDBsInStack(await stack(project, project, true, {}))
  )).then(dbs => dbs.flat());

  const migrationDbsRet = await runForAllMigrations(async (stack, migration, sv) => {
    return getDBsInStack(stack)
  }, false).then(result => Array.from(result.values()).flat());

  return [
    ...coreDbs,
    ...migrationDbsRet,
  ]
}

async function getAllGcpDbs(): Promise<cloudsql.protos.google.cloud.sql.v1.IDatabaseInstance[]> {
  const gcp_project = config.requireEnv('CLOUDSDK_CORE_PROJECT');
  const filter = `settings.userLabels.cluster:${CLUSTER_BASENAME}`;
  // console.log(filter);
  const request = {
    project: gcp_project,
    filter: filter

  };
  const result = await gcpSqlClient.list(request)
  return result[0].items ?? [];
}

function prettyPrintDb(db: cloudsql.protos.google.cloud.sql.v1.IDatabaseInstance) {
  const createTimeSeconds = db.createTime?.seconds;
  const createTime = createTimeSeconds ? new Date(createTimeSeconds as number * 1000).toDateString() : 'unknown';
  const size = `${db.settings?.dataDiskSizeGb?.value ?? 'unknown'} GB`;
  console.log(`* Database: ${db.name} (State: ${db.state}, created: ${createTime}, size: ${size})`);
}

async function deleteDb(db: cloudsql.protos.google.cloud.sql.v1.IDatabaseInstance) {
  const request = {
    instance: db.name,
    project: db.project
  }
  console.log(`Deleting ${db.name}...`);
  gcpSqlClient.delete(request);
  console.log(`Done deleting ${db.name}`);
}

async function runPurgeUnusedDbs() {

  program
    .option('-y, --yes', 'Auto-accept all prompts')
    .parse(process.argv);

  const options = program.opts();
  const autoAccept = options.yes;

  const usedDbs = await getAllPulumiDbs();
  // DatabaseInstance.id is a string, but ts insists on it being an Output<string>, so we force-cast it via an unknown cast
  const usedDbNames: string[] = usedDbs.map((db: gcp.sql.DatabaseInstance) => db.id) as unknown as string[];

  const allDbs = await getAllGcpDbs();

  const unusedDbs = allDbs.filter(db => !usedDbNames.some(usedDb => usedDb == db.name));

  if (unusedDbs.length != allDbs.length - usedDbNames.length) {
    console.warn("Warning: There are some databases in Pulumi that were not found in GCP");
  }

  if (unusedDbs.length == 0) {
    console.log("No unused databases found");
    return;
  }
  console.log(`About to delete the following ${unusedDbs.length} database instances:`);
  unusedDbs.forEach(db => prettyPrintDb(db));

  if (autoAccept) {
    console.log('Auto-accepting');
    unusedDbs.forEach(async db => { deleteDb(db); });
  } else {

    const rl = readline.createInterface({
      input: process.stdin,
      output: process.stdout
    });
    rl.question('\nDo you want to proceed with deleting these DB instances? [y/n] ', async (answer) => {
      if (answer === 'y') {
        console.log('Deleting databases');
        unusedDbs.forEach(async db => { deleteDb(db); });
      } else {
        console.log('Aborting');
      }
      rl.close();
    });
  }
}

runPurgeUnusedDbs().catch(e => {
  console.error(e);
  process.exit(1);
});
