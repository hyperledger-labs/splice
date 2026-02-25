// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { installController } from './controller';
import { installDockerRegistryMirror } from './dockerMirror';
import { installRunnerScaleSets } from './runners';
import { ghaConfigs } from './config';

installDockerRegistryMirror();
const controller = installController();
ghaConfigs.forEach(config => {
    installRunnerScaleSets(controller, config);
});
