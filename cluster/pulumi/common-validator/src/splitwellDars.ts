import * as fs from 'fs';
import * as semver from 'semver';
import { SPLICE_ROOT } from 'splice-pulumi-common';
import { splitwellConfig } from 'splice-pulumi-common/src/config/splitwellConfig';

export const splitwellDarPaths = fs
  .readdirSync(`${SPLICE_ROOT}/daml/dars`)
  .filter(file => {
    const match = file.match(/splitwell-(\d+\.\d+\.\d+)\.dar/);
    if (match) {
      const darVersion = match[1];
      return splitwellConfig?.maxDarVersion
        ? semver.gte(splitwellConfig.maxDarVersion, darVersion)
        : true;
    }
    return false;
  })
  .map(file => `splice-node/dars/${file}`);
