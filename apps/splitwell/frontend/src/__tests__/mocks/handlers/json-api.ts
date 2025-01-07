// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { RestHandler, rest } from 'msw';

import { alicePartyId } from '../constants';

export const buildJsonApiMock = (jsonApiUrl: string): RestHandler[] => [
  rest.get(`${jsonApiUrl}v2/users/:userid`, (_, res, ctx) => {
    console.error('yolo');
    return res(
      ctx.json({
        user: {
          primaryParty: alicePartyId,
          userId: 'alice',
        },
        status: 200,
      })
    );
  }),
];
