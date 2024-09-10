import { config, processMigrationId } from 'splice-pulumi-common';

import { installNode } from './installNode';

const migrationId = processMigrationId(config.requireEnv('SPLICE_MIGRATION_ID'))!;
const sv = config.requireEnv('SPLICE_SV');

// eslint-disable-next-line @typescript-eslint/no-floating-promises
installNode(migrationId, sv);
