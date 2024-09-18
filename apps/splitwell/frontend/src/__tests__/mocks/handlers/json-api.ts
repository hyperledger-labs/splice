// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { RestHandler, rest } from 'msw';

import { GroupRequest } from '@daml.js/splitwell/lib/Splice/Splitwell';

import { alicePartyId, groupName, splitwellProviderPartyId } from '../constants';

export const buildJsonApiMock = (jsonApiUrl: string): RestHandler[] => [
  rest.post(`${jsonApiUrl}v1/user`, (_, res, ctx) => {
    return res(
      ctx.json({
        result: {
          primaryParty: alicePartyId,
          userId: 'alice',
        },
        status: 200,
      })
    );
  }),
  rest.post(`${jsonApiUrl}v1/exercise`, (_, res, ctx) => {
    return res(
      ctx.json({
        result: {
          completionOffset: '000000000000000031',
          events: [
            {
              created: {
                agreementText: '',
                contractId:
                  '009d82979f611cae1886c5f87e845375a0b4faeb3a2c56e1adc10761c64d7ff83aca021220c7f65eecbd331dbdd155c02abb61caa364f082d2c3e73d177d3f261f8b17d48d',
                observers: [],
                payload: GroupRequest.encode({
                  group: {
                    provider: splitwellProviderPartyId,
                    id: { unpack: groupName },
                    owner: alicePartyId,
                    members: [],
                    dso: 'DSO::122065980b045703ed871be9b93afb28b61c874b667434259d1df090096837e3ffd0',
                    acceptDuration: { microseconds: '300000000' },
                  },
                }),
                signatories: [alicePartyId, splitwellProviderPartyId],
                templateId:
                  'cbca8a4f8d6170f38cd7a5c9cc0371cc3ccb4fb5bf5daf0702aa2c3849ac6bde:Splice.Splitwell:GroupRequest',
              },
            },
          ],
          exerciseResult:
            '009d82979f611cae1886c5f87e845375a0b4faeb3a2c56e1adc10761c64d7ff83aca021220c7f65eecbd331dbdd155c02abb61caa364f082d2c3e73d177d3f261f8b17d48d',
        },
        status: 200,
      })
    );
  }),
];
