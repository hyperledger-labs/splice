// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { externalIpRangesFile, loadJsonFromFile } from '@canton-network/splice-pulumi-common';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager';

import { infraConfig } from '../config';

type IpRangesDict = { [key: string]: IpRangesDict } | string[];

function extractIpRanges(x: IpRangesDict, svsOnly: boolean = false): string[] {
  if (svsOnly) {
    if (Array.isArray(x)) {
      throw new Error('Cannot distinguish SV IP ranges from non-SV IP ranges in an array');
    }
    return extractIpRanges(x['svs'], false);
  } else {
    return Array.isArray(x)
      ? x
      : Object.keys(x).reduce((acc: string[], k: string) => acc.concat(extractIpRanges(x[k])), []);
  }
}

export function loadInternalWhitelistedIps(): pulumi.Output<string[]> {
  const excludedIps = infraConfig.ipWhitelisting?.excludedIps || [];

  return getSecretVersionOutput({
    secret: 'pulumi-internal-whitelists',
  }).apply(whitelists => {
    const secretData = whitelists.secretData;
    const json = JSON.parse(secretData);
    const ret: string[] = [];
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    json.forEach((ip: any) => {
      ret.push(ip);
    });
    const ips = ret.filter(ip => excludedIps.indexOf(ip) < 0);
    return [...new Set(ips)];
  });
}

export function loadIPRanges(svsOnly: boolean = false): pulumi.Output<string[]> {
  const file = externalIpRangesFile();
  const externalIpRanges = file ? extractIpRanges(loadJsonFromFile(file), svsOnly) : [];

  const configWhitelistedIps = infraConfig.ipWhitelisting?.extraWhitelistedIngress || [];
  const excludedIps = infraConfig.ipWhitelisting?.excludedIps || [];

  return loadInternalWhitelistedIps().apply(whitelists => {
    const ips = whitelists
      .concat(externalIpRanges)
      .concat(configWhitelistedIps)
      .filter(ip => excludedIps.indexOf(ip) < 0);
    return [...new Set(ips)];
  });
}
