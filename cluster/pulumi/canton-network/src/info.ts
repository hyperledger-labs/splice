import * as pulumi from "@pulumi/pulumi";

import {
  DecentralizedSynchronizerMigrationConfig,
  ExactNamespace,
  installSpliceHelmChart,
} from 'splice-pulumi-common';

import {
  DecentralizedSynchronizerNode,
} from 'splice-pulumi-common-sv';

import {
  CnChartVersion,
} from 'splice-pulumi-common/src/artifacts';

export function installInfo(
  xns: ExactNamespace,
  host: string,
  gateway: string,
  decentralizedSynchronizerMigrationConfig: DecentralizedSynchronizerMigrationConfig,
  decentralizedSynchronizerNode: DecentralizedSynchronizerNode,
  scanUrl: string,
) {
  function cnChartVerstionToString(version: CnChartVersion): string {
    return version.type === 'remote' ? version.version : 'local';
  }

  const infoValues = {
    runtimeDetails: {
      scanUrl: scanUrl,
    },
    deploymentDetails: {
      network: "XXXX", // FIXME: Placeholder, replace with actual network name
      sv: {
        version: cnChartVerstionToString(decentralizedSynchronizerMigrationConfig.active.version),
      },
      configDigest: {
        allowedIpRanges: {
          type: 'md5',
          value: "XXXX", // FIXME: Placeholder, replace with actual allowed IP ranges digest
        },
        approvedSvIdentities: {
          type: 'md5',
          value: "XXXX", // FIXME: Placeholder, replace with actual approved SV identities digest
        },
      },
      synchronizer: {
        active: {
          chainIdSuffix: "XXXX", // FIXME: Placeholder, replace with actual chain ID suffix
          migrationId: decentralizedSynchronizerMigrationConfig.active.id,
          version: cnChartVerstionToString(decentralizedSynchronizerMigrationConfig.active.version),
        },
        legacy: decentralizedSynchronizerMigrationConfig.legacy ? {
          chainIdSuffix: "XXXX", // FIXME: Placeholder, replace with actual legacy chain ID suffix
          migrationId: decentralizedSynchronizerMigrationConfig.legacy.id,
          version: cnChartVerstionToString(decentralizedSynchronizerMigrationConfig.legacy.version),
        } : null,
        staging: decentralizedSynchronizerMigrationConfig.upgrade ? {
          chainIdSuffix: "XXXX", // FIXME: Placeholder, replace with actual staging chain ID suffix
          migrationId: decentralizedSynchronizerMigrationConfig.upgrade.id,
          version: cnChartVerstionToString(decentralizedSynchronizerMigrationConfig.upgrade.version),
        } : null,
      },
    },
    istioVirtualService: {
      host: host,
      gateway: gateway,
    },
  };

  const info = installSpliceHelmChart(xns, 'info', 'splice-info', infoValues, decentralizedSynchronizerMigrationConfig.active.version);

  return info;
}
