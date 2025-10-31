// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as semver from 'semver';
import { allowDowngrade } from '@lfdecentralizedtrust/splice-pulumi-common';
import { PolicyPack, validateResourceOfType } from '@pulumi/policy';
import { execSync } from 'child_process';

interface HelmRelease {
  name: string;
  namespace: string;
  revision: string;
  updated: string;
  status: string;
  chart: string;
  app_version: string;
}

function getHelmAppVersion(releaseName: string, namespace: string): string | undefined {
  try {
    const command = `helm list --namespace ${namespace} --output json`;
    const stdout = execSync(command, { encoding: 'utf-8' });
    const releases: HelmRelease[] = JSON.parse(stdout);
    const targetRelease = releases.find(release => release.name === releaseName);
    return targetRelease ? targetRelease.app_version : undefined;
  } catch (error) {
    console.warn(`Error fetching Helm releases in namespace "${namespace}":`, error);
    return undefined;
  }
}

export function applyDowngradePolicy(): PolicyPack {
  return new PolicyPack('downgrade-policy', {
    policies: [
      {
        name: 'prevent-app-version-downgrade',
        description:
          "Prohibits downgrading the 'appVersion' label on Kubernetes Deployments if `allowDowngrade` is not set.",
        enforcementLevel: 'mandatory',
        validateResource: validateResourceOfType(
          k8s.helm.v3.Release,
          (_, args, reportViolation) => {
            if (allowDowngrade) {
              return;
            }

            const currentVersion = getHelmAppVersion(args.props.name, args.props.namespace);
            if (!currentVersion) {
              return;
            }

            const newVersion = args.props.version as string;
            if (newVersion) {
              if (semver.lt(newVersion, currentVersion)) {
                reportViolation(
                  `Deployment '${args.name}' cannot be downgraded from appVersion '${currentVersion}' to '${newVersion}'.` +
                    ` To override, configure this policy with 'allowDowngrades: true'.`
                );
              }
            }
          }
        ),
      },
    ],
  });
}
