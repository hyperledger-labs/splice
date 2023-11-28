import { RestHandler, rest } from 'msw';
import {
  ErrorResponse,
  LookupEntryByNameResponse,
  LookupEntryByPartyResponse,
  ListEntriesResponse,
  GetSvcPartyIdResponse,
} from 'scan-openapi';

import { CnsEntry } from '@daml.js/cns/lib/CN/Cns';

import { alicePartyId, bobPartyId } from '../constants';

export const buildScanMock = (scanUrl: string): RestHandler[] => [
  rest.get(`${scanUrl}/v0/svc-party-id`, (_, res, ctx) => {
    return res(
      ctx.json<GetSvcPartyIdResponse>({
        svc_party_id: 'SVC::1220809612f787469c92b924ad1d32f1cbc0bdbd4eeda55a50469250bcf64b8becf2',
      })
    );
  }),
  rest.get<null, { partyId: string }, LookupEntryByPartyResponse | ErrorResponse>(
    `${scanUrl}/v0/cns-entries/by-party/:partyId`,
    (req, res, ctx) => {
      if (req.params.partyId === alicePartyId) {
        return res(
          ctx.json<LookupEntryByPartyResponse>({
            entry: {
              template_id:
                '5c14f1a1caa3f7916ccb572ea7b8685dce90b90c5307dd6aeefd7b711013a7ea:CN.Cns:CnsEntry',
              contract_id:
                '00c8e178f8b0b2c2955103b3fa59ccdc5f34861c4bcf659844c2959ba9febf3f61ca0212207e6c7b0db1b456c2f3f23c3b0c75b02dfc0c470cd1ea3fb603a01527e414c922',
              payload: CnsEntry.encode({
                name: 'alice.unverified.cns',
                svc: 'SVC::1220aafbf2c3901ecf0766fb6a65e9eac904f9f320829b9f3202592f7d57c0da9a70',
                url: 'https://alice-url.cns.com',
                description: '',
                expiresAt: '2024-01-07T14:50:26.364476Z',
                user: alicePartyId,
              }),
              created_event_blob: '',
              created_at: '2023-10-09T14:50:26.364476Z',
            },
          })
        );
      }

      if (req.params.partyId === bobPartyId) {
        return res(
          ctx.json<LookupEntryByPartyResponse>({
            entry: {
              template_id:
                '5c14f1a1caa3f7916ccb572ea7b8685dce90b90c5307dd6aeefd7b711013a7ea:CN.Directory:DirectoryEntry',
              contract_id:
                '00c8e178f8b0b2c2955103b3fa59ccdc5f34861c4bcf659844c2959ba9febf3f61ca0212207e6c7b0db1b456c2f3f23c3b0c75b02dfc0c470cd1ea3fb603a01527e414c922',
              payload: CnsEntry.encode({
                name: 'bob.unverified.cns',
                svc: 'SVC::1220aafbf2c3901ecf0766fb6a65e9eac904f9f320829b9f3202592f7d57c0da9a70',
                url: 'https://bob-url.cns.com',
                description: '',
                expiresAt: '2024-01-07T14:50:26.364476Z',
                user: bobPartyId,
              }),
              created_event_blob: '',
              created_at: '2023-10-09T14:50:26.364476Z',
            },
          })
        );
      }

      return res(
        ctx.status(404),
        ctx.json<ErrorResponse>({
          error: `No cns entry found for party: ${alicePartyId}`,
        })
      );
    }
  ),
  rest.get<null, { name: string }, LookupEntryByNameResponse | ErrorResponse>(
    `${scanUrl}/v0/cns-entries/by-name`,
    (_, res, ctx) => {
      return res(
        ctx.status(404),
        ctx.json({
          error: `No cns entry found for party: ${alicePartyId}`,
        })
      );
    }
  ),
  rest.get<null, { partyId: string }, ListEntriesResponse>(
    `${scanUrl}/v0/cns-entries`,
    (_, res, ctx) => {
      return res(
        ctx.json({
          entries: [],
        })
      );
    }
  ),
];
