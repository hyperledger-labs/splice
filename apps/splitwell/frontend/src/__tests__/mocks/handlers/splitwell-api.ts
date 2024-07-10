// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { RestHandler, rest } from 'msw';
import {
  GetConnectedDomainsResponse,
  GetProviderPartyIdResponse,
  GetSplitwellDomainIdsResponse,
  ListAcceptedGroupInvitesResponse,
  ListBalanceUpdatesResponse,
  ListBalancesResponse,
  ListGroupInvitesResponse,
  ListGroupsResponse,
  ListSplitwellInstallsResponse,
  ListSplitwellRulesResponse,
} from 'splitwell-openapi';

import { Group, SplitwellRules } from '@daml.js/splitwell/lib/Splice/Splitwell';

import {
  alicePartyId,
  groupName,
  splitwellDomainId,
  splitwellInstallCid,
  splitwellProviderPartyId,
} from '../constants';

export const buildSplitwellMock = (splitwellUrl: string): RestHandler[] => [
  rest.get(`${splitwellUrl}/provider-party-id`, (_, res, ctx) => {
    return res(
      ctx.json<GetProviderPartyIdResponse>({
        provider_party_id: splitwellProviderPartyId,
      })
    );
  }),
  rest.get(`${splitwellUrl}/splitwell-installs`, (_, res, ctx) => {
    return res(
      ctx.json<ListSplitwellInstallsResponse>({
        installs: [
          {
            contract_id: splitwellInstallCid,
            domain_id: splitwellDomainId,
          },
        ],
      })
    );
  }),
  rest.get(`${splitwellUrl}/splitwell-rules`, (_, res, ctx) => {
    return res(
      ctx.json<ListSplitwellRulesResponse>({
        rules: [
          {
            contract: {
              template_id:
                'cbca8a4f8d6170f38cd7a5c9cc0371cc3ccb4fb5bf5daf0702aa2c3849ac6bde:Splice.Splitwell:SplitwellRules',
              contract_id:
                '00f2402e664650fdb4f40e42f79facd0e007c344743c67b69da3705a2c171dbb26ca021220345fc60e560266dbba9f2544d07b0292539275995020153ccca14c5076df9a55',
              payload: SplitwellRules.encode({
                provider: splitwellProviderPartyId,
              }),
              created_event_blob: '',
              created_at: '2023-10-05T15:35:40.054390Z',
            },
            domain_id: splitwellDomainId,
          },
        ],
      })
    );
  }),
  rest.get(`${splitwellUrl}/connected-domains`, (_, res, ctx) => {
    return res(
      ctx.json<GetConnectedDomainsResponse>({
        domain_ids: [
          'global-domain::1220809612f787469c92b924ad1d32f1cbc0bdbd4eeda55a50469250bcf64b8becf2',
          splitwellDomainId,
        ],
      })
    );
  }),
  rest.get(`${splitwellUrl}/splitwell-domains`, (_, res, ctx) => {
    return res(
      ctx.json<GetSplitwellDomainIdsResponse>({
        preferred: splitwellDomainId,
        other_domain_ids: [],
      })
    );
  }),

  rest.get(`${splitwellUrl}/group-invites`, (_, res, ctx) => {
    return res(
      ctx.json<ListGroupInvitesResponse>({
        group_invites: [],
      })
    );
  }),
  rest.get(`${splitwellUrl}/groups`, (_, res, ctx) => {
    return res(
      ctx.json<ListGroupsResponse>({
        groups: [
          {
            contract: {
              template_id:
                'cbca8a4f8d6170f38cd7a5c9cc0371cc3ccb4fb5bf5daf0702aa2c3849ac6bde:Splice.Splitwell:Group',
              contract_id:
                '00857d7d5500196ffed47bc83b2d709a5b841c8724a6c03c9024ed3fb16054a5b0ca0212204f5987937925504ea6ea64c731b6637fc9d184760c1266eab55b02c9399d23f8',
              payload: Group.encode({
                provider: splitwellProviderPartyId,
                id: {
                  unpack: groupName,
                },
                owner: alicePartyId,
                members: [],
                dso: 'DSO::122065980b045703ed871be9b93afb28b61c874b667434259d1df090096837e3ffd0',
                acceptDuration: {
                  microseconds: '300000000',
                },
              }),
              created_event_blob: '',
              created_at: '2023-10-06T09:20:54.077318Z',
            },
            domain_id: splitwellDomainId,
          },
        ],
      })
    );
  }),
  rest.get(`${splitwellUrl}/balances`, (_, res, ctx) => {
    return res(ctx.json<ListBalancesResponse>({ balances: {} }));
  }),
  rest.get(`${splitwellUrl}/balance-updates`, (_, res, ctx) => {
    return res(ctx.json<ListBalanceUpdatesResponse>({ balance_updates: [] }));
  }),
  rest.get(`${splitwellUrl}/accepted-group-invites`, (_, res, ctx) => {
    return res(ctx.json<ListAcceptedGroupInvitesResponse>({ accepted_group_invites: [] }));
  }),
];
