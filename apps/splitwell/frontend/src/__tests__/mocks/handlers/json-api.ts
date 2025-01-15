// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { RestHandler, rest } from 'msw';

import { alicePartyId } from '../constants';

export const domainDisconnectErrorResponse = {
  status: 400,
  errors: [
    'simulating NOT_CONNECTED_TO_ANY_DOMAIN(1,1): This participant is not connected to domain global-domain::12345',
  ],
};

export const buildJsonApiMock = (jsonApiUrl: string): RestHandler[] => [
  rest.get(`${jsonApiUrl}v2/users/:userid`, (_, res, ctx) => {
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

export const exerciseCreateInviteResponse = {
  transaction_tree: {
    update_id: '1220d958bc9e4063238be2ee103fa4251d10e049164be14a212bc4bd06223824633a',
    command_id: '5fcfa413-ee7b-492e-9023-42827a9bf8ed',
    workflow_id: '',
    effective_at: '2025-01-14T11:13:49.525283Z',
    offset: 15885,
    events_by_id: {
      '#1220d958bc9e4063238be2ee103fa4251d10e049164be14a212bc4bd06223824633a:0': {
        ExercisedTreeEvent: {
          event_id: '#1220d958bc9e4063238be2ee103fa4251d10e049164be14a212bc4bd06223824633a:0',
          contract_id:
            '00f9d9790be8adcc7e9e75f4d4471e64689e4bc3617b64c08a65d28d5fc603d735ca1012205a365b6c64ec687ad0f4e175bc76a21a7754be4d0b86e3198b4093d1106a12e3',
          template_id:
            '841d1c9c86b5c8f3a39059459ecd8febedf7703e18f117300bb0ebf4423db096:Splice.Splitwell:SplitwellRules',
          interface_id: null,
          choice: 'SplitwellRules_CreateInvite',
          choice_argument: {
            group:
              '00b478d6ff2fa3fac22f899bc99f8541cd43c6dcab949de810f79879903bf6f332ca101220ca0bb6d9574a7af5dad52388b6f0a48f0eea15cb06c38a908c94b08e45c3bb7d',
            user: 'alice__wallet__user::12202486f977b9d49bbd77ba8a3624b4d13c211372977ce5726bb5e93fa094be7e09',
          },
          acting_parties: [
            'alice__wallet__user::12202486f977b9d49bbd77ba8a3624b4d13c211372977ce5726bb5e93fa094be7e09',
          ],
          consuming: false,
          witness_parties: [
            'alice__wallet__user::12202486f977b9d49bbd77ba8a3624b4d13c211372977ce5726bb5e93fa094be7e09',
          ],
          child_event_ids: [
            '#1220d958bc9e4063238be2ee103fa4251d10e049164be14a212bc4bd06223824633a:2',
          ],
          exercise_result:
            '00afbc44a381f3a1d6b2ec5bb41a828accd50ecdab184bc4398df5444207f4496dca1012201d20d6e5c0bb9f2c6096faa4e187f1f4afbe4d60a21f602064aa871469464802',
          package_name: 'splitwell',
        },
      },
      '#1220d958bc9e4063238be2ee103fa4251d10e049164be14a212bc4bd06223824633a:2': {
        ExercisedTreeEvent: {
          event_id: '#1220d958bc9e4063238be2ee103fa4251d10e049164be14a212bc4bd06223824633a:2',
          contract_id:
            '00b478d6ff2fa3fac22f899bc99f8541cd43c6dcab949de810f79879903bf6f332ca101220ca0bb6d9574a7af5dad52388b6f0a48f0eea15cb06c38a908c94b08e45c3bb7d',
          template_id:
            '841d1c9c86b5c8f3a39059459ecd8febedf7703e18f117300bb0ebf4423db096:Splice.Splitwell:Group',
          interface_id: null,
          choice: 'Group_CreateInvite',
          choice_argument: {},
          acting_parties: [
            'alice__wallet__user::12202486f977b9d49bbd77ba8a3624b4d13c211372977ce5726bb5e93fa094be7e09',
          ],
          consuming: false,
          witness_parties: [
            'alice__wallet__user::12202486f977b9d49bbd77ba8a3624b4d13c211372977ce5726bb5e93fa094be7e09',
          ],
          child_event_ids: [
            '#1220d958bc9e4063238be2ee103fa4251d10e049164be14a212bc4bd06223824633a:3',
          ],
          exercise_result:
            '00afbc44a381f3a1d6b2ec5bb41a828accd50ecdab184bc4398df5444207f4496dca1012201d20d6e5c0bb9f2c6096faa4e187f1f4afbe4d60a21f602064aa871469464802',
          package_name: 'splitwell',
        },
      },
      '#1220d958bc9e4063238be2ee103fa4251d10e049164be14a212bc4bd06223824633a:3': {
        CreatedTreeEvent: {
          value: {
            event_id: '#1220d958bc9e4063238be2ee103fa4251d10e049164be14a212bc4bd06223824633a:3',
            contract_id:
              '00afbc44a381f3a1d6b2ec5bb41a828accd50ecdab184bc4398df5444207f4496dca1012201d20d6e5c0bb9f2c6096faa4e187f1f4afbe4d60a21f602064aa871469464802',
            template_id:
              '841d1c9c86b5c8f3a39059459ecd8febedf7703e18f117300bb0ebf4423db096:Splice.Splitwell:GroupInvite',
            contract_key: null,
            create_argument: {
              group: {
                owner:
                  'alice__wallet__user::12202486f977b9d49bbd77ba8a3624b4d13c211372977ce5726bb5e93fa094be7e09',
                dso: 'DSO::12204d777a47c87462c36fbdad2686612e2c1a513f9e9d98d48496de84c93bb80379',
                members: [],
                id: {
                  unpack: 'testG2',
                },
                provider:
                  'splitwell__provider::1220bfe1f427aef9162c4877cea884675584d0f01c8a77b8ce733713e8cc6c0a02c5',
                acceptDuration: {
                  microseconds: '300000000',
                },
              },
            },
            created_event_blob: '',
            interface_views: [],
            witness_parties: [
              'alice__wallet__user::12202486f977b9d49bbd77ba8a3624b4d13c211372977ce5726bb5e93fa094be7e09',
            ],
            signatories: [
              'alice__wallet__user::12202486f977b9d49bbd77ba8a3624b4d13c211372977ce5726bb5e93fa094be7e09',
              'splitwell__provider::1220bfe1f427aef9162c4877cea884675584d0f01c8a77b8ce733713e8cc6c0a02c5',
            ],
            observers: [],
            created_at: '2025-01-14T11:13:49.525283Z',
            package_name: 'splitwell',
          },
        },
      },
    },
    root_event_ids: ['#1220d958bc9e4063238be2ee103fa4251d10e049164be14a212bc4bd06223824633a:0'],
    domain_id: 'splitwell::1220dd6efb42b441d7a82bc0320a73f181e7da8f7f889703361fed95aa01820c124e',
    trace_context: {
      traceparent: '00-31d873d33da296b8bb10123a98487752-636ddcf35e52d4ac-01',
      tracestate: null,
    },
    record_time: '2025-01-14T11:13:49.790867Z',
  },
};
