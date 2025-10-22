// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as fs from 'fs';
import {
  clusterNetwork,
  DecentralizedSynchronizerMigrationConfig,
  ExactNamespace,
  externalIpRangesFile,
  installSpliceHelmChart,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import {
  approvedSvIdentitiesFile,
  getChainIdSuffix,
} from '@lfdecentralizedtrust/splice-pulumi-common-sv';
import { CnChartVersion } from '@lfdecentralizedtrust/splice-pulumi-common/src/artifacts';
import { Resource } from '@pulumi/pulumi';
import { createHash } from 'crypto';

export function installInfo(
  xns: ExactNamespace,
  host: string,
  gateway: string,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  scanUrl: string,
  scanDependency: Resource
): void {
  function cnChartVerstionToString(version: CnChartVersion): string {
    return version.type === 'remote' ? version.version : 'local';
  }

  function md5(str: string): string {
    return createHash('md5').update(str).digest('hex');
  }

  function fileDigestMd5(file: string | undefined): string {
    const data = file ? fs.readFileSync(file, 'utf8') : '';
    return md5(data);
  }

  const infoValues = {
    runtimeDetails: {
      scanUrl: scanUrl,
    },
    deploymentDetails: {
      network: clusterNetwork,
      sv: {
        version: cnChartVerstionToString(decentralizedSynchronizerMigrationConfig.active.version),
      },
      configDigest: {
        allowedIpRanges: {
          type: 'md5',
          value: fileDigestMd5(externalIpRangesFile()),
        },
        approvedSvIdentities: {
          type: 'md5',
          value: fileDigestMd5(approvedSvIdentitiesFile()),
        },
      },
      synchronizer: {
        active: {
          chainIdSuffix: getChainIdSuffix(),
          migrationId: decentralizedSynchronizerMigrationConfig.active.id,
          version: cnChartVerstionToString(decentralizedSynchronizerMigrationConfig.active.version),
        },
        legacy: decentralizedSynchronizerMigrationConfig.legacy
          ? {
              chainIdSuffix: getChainIdSuffix(),
              migrationId: decentralizedSynchronizerMigrationConfig.legacy.id,
              version: cnChartVerstionToString(
                decentralizedSynchronizerMigrationConfig.legacy.version
              ),
            }
          : null,
        staging: decentralizedSynchronizerMigrationConfig.upgrade
          ? {
              chainIdSuffix: getChainIdSuffix(),
              migrationId: decentralizedSynchronizerMigrationConfig.upgrade.id,
              version: cnChartVerstionToString(
                decentralizedSynchronizerMigrationConfig.upgrade.version
              ),
            }
          : null,
      },
    },
    istioVirtualService: {
      host: host,
      gateway: gateway,
    },
  };

  installSpliceHelmChart(
    xns,
    'info',
    'splice-info',
    infoValues,
    decentralizedSynchronizerMigrationConfig.active.version,
    {
      dependsOn: [scanDependency],
    }
  );
}
