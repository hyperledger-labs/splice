// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { setupWorker, SetupWorker } from 'msw';

import { buildSvMock } from './handlers/sv-api';

const URL = 'http://localhost:5014/api/sv';

export const worker: SetupWorker = setupWorker(...buildSvMock(URL));
