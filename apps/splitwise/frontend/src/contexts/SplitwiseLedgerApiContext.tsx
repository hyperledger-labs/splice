import { Contract, LedgerApiClient, buildLedgerApiClientInterface } from 'common-frontend';
import { GroupKey } from 'common-protobuf/com/daml/network/splitwise/v0/splitwise_service_pb';

import { OpenMiningRound } from '@daml.js/canton-coin/lib/CC/Round';
import {
  AcceptedGroupInvite,
  GroupInvite,
  SplitwiseInstall,
} from '@daml.js/splitwise/lib/CN/Splitwise';
import {
  AcceptedAppPayment,
  AcceptedAppMultiPayment,
  ReceiverQuantity,
} from '@daml.js/wallet/lib/CN/Wallet';
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

  async initiateTransfer(
    user: string,
    provider: string,
    key: GroupKey,
    receiver: string,
    quantity: string
  ) {
    await this.exerciseByKey(
      [user],
      [],
      SplitwiseInstall.SplitwiseInstall_InitiateTransfer,
      { _1: user, _2: provider },
      {
        groupKey: {
          owner: key.getOwnerPartyId(),
          provider: key.getProviderPartyId(),
          id: { unpack: key.getId() },
        },
        receiver: receiver,
        quantity: quantity,
      }
    );
  }

  async completeTransfer(
    user: string,
    provider: string,
    key: GroupKey,
    acceptedPaymentContractId: ContractId<AcceptedAppPayment>,
    openRound: ContractId<OpenMiningRound>
  ) {
    const readAs = await this.getUserReadAs(this.userId);
    await this.exerciseByKey(
      [user],
      readAs,
      SplitwiseInstall.SplitwiseInstall_CompleteTransfer,
      { _1: user, _2: provider },
      {
        groupKey: {
          owner: key.getOwnerPartyId(),
          provider: key.getProviderPartyId(),
          id: { unpack: key.getId() },
        },
        acceptedPaymentCid: acceptedPaymentContractId,
        openRound: openRound,
      }
    );
  }

  async initiateMultiTransfer(
    sender: string,
    provider: string,
    key: GroupKey,
    receivers: Map<string, string>
  ) {
    // TODO(#1199) use numeric instead of text fields in UI
    const qs: ReceiverQuantity[] = Array.from(receivers)
      .filter(([_, v]) => v.at(0) === '-')
      .map(([k, v]) => {
        return { receiver: k, quantity: v.substring(1, v.length - 1) };
      });
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
        receiverQuantities: qs,
      }
    );
  }

  async completeMultiTransfer(
    sender: string,
    provider: string,
    key: GroupKey,
    acceptedPaymentContractId: ContractId<AcceptedAppMultiPayment>,
    openRound: ContractId<OpenMiningRound>
  ) {
    const readAs = await this.getUserReadAs(this.userId);
    await this.exerciseByKey(
      [sender],
      readAs,
      SplitwiseInstall.SplitwiseInstall_CompleteMultiTransfer,
      { _1: sender, _2: provider },
      {
        groupKey: {
          owner: key.getOwnerPartyId(),
          provider: key.getProviderPartyId(),
          id: { unpack: key.getId() },
        },
        acceptedPaymentCid: acceptedPaymentContractId,
        openRound: openRound,
      }
    );
  }

  async listAcceptedAppPayments(
    user: string,
    key: GroupKey
  ): Promise<Contract<AcceptedAppPayment>[]> {
    // TODO(M1-92) Improve filtering
    const contracts = await this.queryAcs(user, AcceptedAppPayment);
    return contracts;
  }

  async listAcceptedAppMultiPayments(
    user: string,
    key: GroupKey
  ): Promise<Contract<AcceptedAppMultiPayment>[]> {
    // TODO(M1-92) Improve filtering
    const contracts = await this.queryAcs(user, AcceptedAppMultiPayment);
    return contracts;
  }

  async querySplitwiseInstall(user: string, provider: string) {
    const response = await this.queryAcs(user, SplitwiseInstall);
    return response.find(c => c.payload.user === user && c.payload.provider === provider);
  }
}

export const [SplitwiseLedgerApiClientProvider, useSplitwiseLedgerApiClient] =
  buildLedgerApiClientInterface(SplitwiseLedgerApiClient);
