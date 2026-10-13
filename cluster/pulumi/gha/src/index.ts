// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';
import { GcpProject } from '@canton-network/splice-pulumi-common/src/config/gcpConfig';

import { ghaConfig } from './config';
import { installController } from './controller';
import { installDockerRegistryMirror } from './dockerMirror';
import { installGithubRepo } from './github';
import { manageMainnetHistoryDumpsUser } from './mainnetHistoryDumpsUser';
import { installRunnerScaleSets } from './runners';

installDockerRegistryMirror();
for (const repo of ghaConfig.githubRepos) {
  void pulumi.log.info(`Configuring GHA runner for repository: ${repo}`);
  const runnersNamespaceName = `gha-runners-${repo}`;
  const controller = installController(repo, runnersNamespaceName);
  installRunnerScaleSets(controller, runnersNamespaceName, repo);
  installGithubRepo(repo);
}

if (ghaConfig.mainnetHistoryDumpsUser) {
  manageMainnetHistoryDumpsUser(GcpProject, ghaConfig.mainnetHistoryDumpsUser);
}
