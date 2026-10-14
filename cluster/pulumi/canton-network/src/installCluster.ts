// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  Auth0Client,
  config,
  exactNamespace,
  isDevNet,
  spliceConfig,
} from '@canton-network/splice-pulumi-common';
import { configForSv, coreSvsToDeploy } from '@canton-network/splice-pulumi-common-sv';
import {
  configureScanBigQuery,
  ScanBigQueryArgs,
} from '@canton-network/splice-pulumi-common-sv/src/bigQuery';

import { activeVersion } from '../../common';
import { installChaosMesh } from './chaosMesh';
import { installDocs } from './docs';

/// Toplevel Chart Installs

void pulumi.log.info(`Launching with isDevNet: ${isDevNet}`);

const enableChaosMesh = config.envFlag('ENABLE_CHAOS_MESH');

export async function installCluster(auth0Client: Auth0Client): Promise<void> {
  await pulumi.log.info(
    activeVersion.type === 'local'
      ? 'Using locally built charts by default'
      : `Using charts from the container registry by default, version ${activeVersion.version}`
  );

  const bigQueryArgs = [...iterateBigQueryArgs()];
  if (bigQueryArgs.length > 1) {
    throw new Error(
      `Multiple SVs with BigQuery configuration found: ${bigQueryArgs.map(arg => arg.namespace.logicalName).join(', ')}`
    );
  }
  for (const args of bigQueryArgs) {
    await configureScanBigQuery(args);
  }

  installDocs();

  if (enableChaosMesh) {
    installChaosMesh({ dependsOn: [] });
  }
}

function* iterateBigQueryArgs(): Generator<ScanBigQueryArgs> {
  for (const sv of coreSvsToDeploy) {
    const config = configForSv(sv.nodeName);
    const bigQueryConfig = config?.scanApp?.bigQuery;
    const cloudSqlEnabled = (config.appsPg?.cloudSql ?? spliceConfig.pulumiProjectConfig.cloudSql)
      .enabled;
    if (bigQueryConfig !== undefined && cloudSqlEnabled) {
      const namespace = exactNamespace(sv.nodeName, true, true);
      yield {
        namespace,
        bigQueryConfig: bigQueryConfig,
        scanReference: {
          type: 'external',
          databaseInstanceNamePrefix: `${namespace.logicalName}-cn-apps-pg`,
        },
      };
    }
  }
}
