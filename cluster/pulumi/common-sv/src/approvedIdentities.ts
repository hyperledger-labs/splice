// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import {
  config,
  DeploySvRunbook,
  getPathToPublicConfigFile,
  loadYamlFromFile,
  SvIdKey,
  svKeyFromSecret,
} from '@lfdecentralizedtrust/splice-pulumi-common';
import _ from 'lodash';

import { coreSvsToDeploy, svRunbookConfig } from './svConfigs';

export type ApprovedSvIdentity = {
  name: string;
  publicKey: string | pulumi.Output<string>;
  rewardWeightBps: number;
};

export function approvedSvIdentitiesFile(): string | undefined {
  return getPathToPublicConfigFile('approved-sv-id-values.yaml');
}

function approvedSvIdentitiesFromFile(): ApprovedSvIdentity[] {
  const file = approvedSvIdentitiesFile();
  return file ? loadYamlFromFile(file).approvedSvIdentities : [];
}

function approvedSvIdentitiesFromConfig(): ApprovedSvIdentity[] {
  const approveSvRunbook = DeploySvRunbook || config.envFlag('APPROVE_SV_RUNBOOK');
  const allSvsToApprove = coreSvsToDeploy.concat(approveSvRunbook ? [svRunbookConfig] : []);

  const svIdKeys = allSvsToApprove.reduce<Record<string, pulumi.Output<SvIdKey>>>((acc, conf) => {
    const secretName = conf.svIdKeySecretName ?? conf.nodeName.replaceAll('-', '') + '-id';
    return {
      ...acc,
      [conf.onboardingName]: svKeyFromSecret(secretName),
    };
  }, {});

  return Object.entries(svIdKeys).map<ApprovedSvIdentity>(([onboardingName, keys]) => ({
    name: onboardingName,
    publicKey: keys.publicKey, // we always use that one if we have it, overriding approved-sv-id-values-$CLUSTER.yaml
    rewardWeightBps: 10000, // if already defined in approved-sv-id-values-$CLUSTER.yaml, this will be ignored.
  }));
}

export function approvedSvIdentities(): ApprovedSvIdentity[] {
  const fromFile = approvedSvIdentitiesFromFile();
  const fromConfig = approvedSvIdentitiesFromConfig();

  // We override public keys to the locally configured one,
  // to support using real approved-sv-id-values files on CI clusters that don't have access to the real keys.
  const configuredPublicKeys = fromConfig.reduce(
    (acc, identity) => ({ ...acc, [identity.name]: identity.publicKey }),
    {} as Record<string, string | pulumi.Output<string>>
  );

  return _.uniqBy([...fromFile, ...fromConfig], 'name').map(identity => ({
    ...identity,
    publicKey: configuredPublicKeys[identity.name] ?? identity.publicKey,
  }));
}
