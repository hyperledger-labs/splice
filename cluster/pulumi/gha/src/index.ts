// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { installController } from './controller';
import { installDockerRegistryMirror } from './dockerMirror';
import { installPerformanceTestsServices } from './performanceTests';
import { installRunnerScaleSets } from './runners';

installDockerRegistryMirror();
const controller = installController();
const ghaNamespace = installRunnerScaleSets(controller);
installPerformanceTestsServices(ghaNamespace);
