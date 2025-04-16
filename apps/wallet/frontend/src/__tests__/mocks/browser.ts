// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { setupWorker, SetupWorker } from 'msw';

import { config } from '../setup/config';
import { buildWalletMock } from './handlers/wallet-api';

export const worker: SetupWorker = setupWorker(...buildWalletMock(config.services.validator.url));
