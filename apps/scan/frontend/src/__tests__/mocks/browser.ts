// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { setupWorker, SetupWorker } from 'msw';

import { buildScanMock } from './handlers/scan-api';

const URL = 'http://localhost:5014';

export const worker: SetupWorker = setupWorker(...buildScanMock(URL));
