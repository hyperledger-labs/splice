import { LedgerApiClient, buildLedgerApiClientInterface } from 'common-frontend';

import {
  AcceptedGroupInvite,
  Group,
  GroupInvite,
  SplitwiseInstall,
} from '@daml.js/splitwise/lib/CN/Splitwise';
import { ReceiverQuantity } from '@daml.js/wallet-payments/lib/CN/Wallet/Payment';
import { ContractId } from '@daml/types';

class SplitwiseLedgerApiClient extends LedgerApiClient {
  collectionDuration: string = (5 * 60 * 1000000).toString();
  acceptDuration: string = (5 * 60 * 1000000).toString();

  async requestGroup(user: string, provider: string, svc: string, id: string) {
    const install = await this.getSplitwiseInstall(user, provider);
    await this.exercise(
      [user],
      [],
      SplitwiseInstall.SplitwiseInstall_RequestGroup,
      install.contractId,
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

  async createGroupInvite(
    user: string,
    provider: string,
    group: ContractId<Group>,
    observers: string[]
  ) {
    const install = await this.getSplitwiseInstall(user, provider);
    await this.exercise(
      [user],
      [],
      SplitwiseInstall.SplitwiseInstall_CreateInvite,
      install.contractId,
      {
        group: group,
        observers: observers,
      }
    );
  }

  async acceptInvite(user: string, provider: string, inviteContractId: ContractId<GroupInvite>) {
    const install = await this.getSplitwiseInstall(user, provider);
    await this.exercise(
      [user],
      [],
      SplitwiseInstall.SplitwiseInstall_AcceptInvite,
      install.contractId,
      {
        cid: inviteContractId,
      }
    );
  }
  async joinGroup(
    user: string,
    provider: string,
    group: ContractId<Group>,
    inviteContractId: ContractId<AcceptedGroupInvite>
  ) {
    const install = await this.getSplitwiseInstall(user, provider);
    await this.exercise([user], [], SplitwiseInstall.SplitwiseInstall_Join, install.contractId, {
      group: group,
      cid: inviteContractId,
    });
  }

  async enterPayment(
    user: string,
    provider: string,
    group: ContractId<Group>,
    quantity: string,
    description: string
  ) {
    const install = await this.getSplitwiseInstall(user, provider);
    await this.exercise(
      [user],
      [],
      SplitwiseInstall.SplitwiseInstall_EnterPayment,
      install.contractId,
      {
        group: group,
        quantity: quantity,
        description: description,
      }
    );
  }

  async initiateTransfer(
    sender: string,
    provider: string,
    group: ContractId<Group>,
    receiverQuantities: ReceiverQuantity[]
  ) {
    const install = await this.getSplitwiseInstall(sender, provider);
    return await this.exercise(
      [sender],
      [],
      SplitwiseInstall.SplitwiseInstall_InitiateTransfer,
      install.contractId,
      {
        group: group,
        receiverQuantities: receiverQuantities,
      }
    );
  }

  async getSplitwiseInstall(user: string, provider: string) {
    const install = await this.querySplitwiseInstall(user, provider);
    if (!install) {
      throw new Error('Could not find SplitwiseInstall');
    }
    return install;
  }

  async querySplitwiseInstall(user: string, provider: string) {
    const response = await this.queryAcs(user, SplitwiseInstall);
    return response.find(c => c.payload.user === user && c.payload.provider === provider);
  }
}

export const [SplitwiseLedgerApiClientProvider, useSplitwiseLedgerApiClient] =
  buildLedgerApiClientInterface(SplitwiseLedgerApiClient);
