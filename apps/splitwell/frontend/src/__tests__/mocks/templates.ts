// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/scan-openapi';

import {
  AcceptedGroupInvite,
  BalanceUpdate,
  BalanceUpdateType,
  GroupInvite,
} from '@daml.js/splitwell/lib/Splice/Splitwell';

import { ContractWithState } from '../../../../openapi-ts-client/dist/models/ContractWithState';

export const makeGroupInvite = (provider: string, owner: string, groupName: string): Contract => ({
  template_id:
    'cbca8a4f8d6170f38cd7a5c9cc0371cc3ccb4fb5bf5daf0702aa2c3849ac6bde:Splice.Splitwell:GroupInvite',
  contract_id:
    '008a4f445f23361cf92ffd48bf8556429921060a40c7169dc11c5a28717d7750e3ca021220bcce6356513ce1790a1c525f5e7709be50336235d2c08be698a581a4e2bc2c6d',
  payload: GroupInvite.encode({
    group: {
      provider: provider,
      id: {
        unpack: groupName,
      },
      owner: owner,
      members: [],
      dso: 'DSO::122065980b045703ed871be9b93afb28b61c874b667434259d1df090096837e3ffd0',
      acceptDuration: {
        microseconds: '300000000',
      },
    },
  }),
  created_event_blob: '',
  created_at: '2023-10-06T13:24:12.679640Z',
});

export const makeAcceptedGroupInvite = (
  provider: string,
  owner: string,
  invitee: string,
  groupName: string
): Contract => ({
  template_id:
    'e1d9f49e8143e1cc8a105fabea49506924df3a6d7f497bd89e0334ebbdc4be80:Splice.Splitwell:AcceptedGroupInvite',
  contract_id:
    '009b07644e1035fe72b4af0ad627e678c4af667ee7b8c44aa10c7f98fd7f89b165ca021220b9deaf4a8a931a689c191bbf08136ffbdceb9d6b7926f9b9daf06682743d8f8e',
  payload: AcceptedGroupInvite.encode({
    groupKey: {
      owner: owner,
      provider: provider,
      id: {
        unpack: groupName,
      },
    },
    invitee: invitee,
  }),
  created_event_blob: '',
  created_at: '2023-10-09T15:14:28.412766Z',
});

export const makeBalanceUpdate = (
  provider: string,
  owner: string,
  groupName: string,
  update: BalanceUpdateType,
  contractId: string
): ContractWithState => ({
  contract: {
    template_id:
      'e1d9f49e8143e1cc8a105fabea49506924df3a6d7f497bd89e0334ebbdc4be80:Splice.Splitwell:BalanceUpdate',
    contract_id: contractId,
    payload: BalanceUpdate.encode({
      group: {
        provider: provider,
        id: {
          unpack: groupName,
        },
        owner: owner,
        members: [],
        dso: 'DSO::1220aafbf2c3901ecf0766fb6a65e9eac904f9f320829b9f3202592f7d57c0da9a70',
        acceptDuration: {
          microseconds: '300000000',
        },
      },
      update: update,
    }),
    created_event_blob: '',
    created_at: '2023-10-09T15:00:35.324749Z',
  },
});
