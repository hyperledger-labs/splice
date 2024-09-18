// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { setupServer, SetupServer } from 'msw/node';

import { buildSvMock } from './handlers/sv-api';

export const buildServer = (svApiUrl: string): SetupServer => setupServer(...buildSvMock(svApiUrl));
