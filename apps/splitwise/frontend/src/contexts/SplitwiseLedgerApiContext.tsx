import { LedgerApiClient, buildLedgerApiClientInterface } from 'common-frontend';
import { GroupKey } from 'common-protobuf/com/daml/network/splitwise/v0/splitwise_service_pb';

import {
  AcceptedGroupInvite,
  GroupInvite,
  SplitwiseInstall,
} from '@daml.js/splitwise/lib/CN/Splitwise';
import { ReceiverQuantity } from '@daml.js/wallet/lib/CN/Wallet';
import { ContractId } from '@daml/types';

class SplitwiseLedgerApiClient extends LedgerApiClient {
  collectionDuration: string = (5 * 60 * 1000000).toString();
  acceptDuration: string = (5 * 60 * 1000000).toString();

  async createGroup(user: string, provider: string, svc: string, id: string) {
    await this.exerciseByKey(
      [user],
      [],
      SplitwiseInstall.SplitwiseInstall_CreateGroup,
      { _1: user, _2: provider },
      {
        group: {
          owner: user,
          provider: provider,
          svc: svc,
          id: { unpack: id },
          members: [],
          collectionDuration: { microseconds: this.collectionDuration },
          acceptDuration: { microseconds: this.acceptDuration },
        },
      }
    );
  }

  async createGroupInvite(user: string, provider: string, id: string, observers: string[]) {
    await this.exerciseByKey(
      [user],
      [],
      SplitwiseInstall.SplitwiseInstall_CreateInvite,
      { _1: user, _2: provider },
      {
        groupKey: { owner: user, provider: provider, id: { unpack: id } },
        observers: observers,
      }
    );
  }

  async acceptInvite(user: string, provider: string, inviteContractId: ContractId<GroupInvite>) {
    await this.exerciseByKey(
      [user],
      [],
      SplitwiseInstall.SplitwiseInstall_AcceptInvite,
      { _1: user, _2: provider },
      {
        cid: inviteContractId,
      }
    );
  }
  async joinGroup(
    user: string,
    provider: string,
    inviteContractId: ContractId<AcceptedGroupInvite>
  ) {
    await this.exerciseByKey(
      [user],
      [],
      SplitwiseInstall.SplitwiseInstall_Join,
      { _1: user, _2: provider },
      {
        cid: inviteContractId,
      }
    );
  }

  async enterPayment(
    user: string,
    provider: string,
    key: GroupKey,
    quantity: string,
    description: string
  ) {
    await this.exerciseByKey(
      [user],
      [],
      SplitwiseInstall.SplitwiseInstall_EnterPayment,
      { _1: user, _2: provider },
      {
        groupKey: {
          owner: key.getOwnerPartyId(),
          provider: key.getProviderPartyId(),
          id: { unpack: key.getId() },
        },
        quantity: quantity,
        description: description,
      }
    );
  }

  async initiateMultiTransfer(
    sender: string,
    provider: string,
    key: GroupKey,
    receiverQuantities: ReceiverQuantity[]
  ) {
    return await this.exerciseByKey(
      [sender],
      [],
      SplitwiseInstall.SplitwiseInstall_InitiateMultiTransfer,
      { _1: sender, _2: provider },
      {
        groupKey: {
          owner: key.getOwnerPartyId(),
          provider: key.getProviderPartyId(),
          id: { unpack: key.getId() },
        },
        receiverQuantities: receiverQuantities,
      }
    );
  }

  async querySplitwiseInstall(user: string, provider: string) {
    const response = await this.queryAcs(user, SplitwiseInstall);
    return response.find(c => c.payload.user === user && c.payload.provider === provider);
  }
}

export const [SplitwiseLedgerApiClientProvider, useSplitwiseLedgerApiClient] =
  buildLedgerApiClientInterface(SplitwiseLedgerApiClient);
