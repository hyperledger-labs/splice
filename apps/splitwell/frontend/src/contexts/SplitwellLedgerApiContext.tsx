import { LedgerApiClient, buildLedgerApiClientInterface, Contract } from 'common-frontend';

import {
  AcceptedGroupInvite,
  Group,
  GroupInvite,
  SplitwellInstall,
} from '@daml.js/splitwell/lib/CN/Splitwell';
import { ReceiverCCAmount } from '@daml.js/wallet-payments/lib/CN/Wallet/Payment';
import { ContractId } from '@daml/types';

class SplitwellLedgerApiClient extends LedgerApiClient {
  collectionDuration: string = (5 * 60 * 1000000).toString();
  acceptDuration: string = (5 * 60 * 1000000).toString();

  async requestGroup(user: string, provider: string, svc: string, id: string, domainId: string) {
    const install = await this.getSplitwellInstall(user, provider);
    await this.exercise(
      [user],
      [],
      SplitwellInstall.SplitwellInstall_RequestGroup,
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
      },
      domainId
    );
  }

  async createGroupInvite(
    user: string,
    provider: string,
    group: ContractId<Group>,
    domainId: string
  ) {
    const install = await this.getSplitwellInstall(user, provider);
    await this.exercise(
      [user],
      [],
      SplitwellInstall.SplitwellInstall_CreateInvite,
      install.contractId,
      {
        group: group,
      },
      domainId
    );
  }

  async acceptInvite(
    user: string,
    provider: string,
    inviteContractId: ContractId<GroupInvite>,
    domainId: string,
    groupInvite: Contract<GroupInvite>
  ) {
    const install = await this.getSplitwellInstall(user, provider);
    await this.exercise(
      [user],
      [],
      SplitwellInstall.SplitwellInstall_AcceptInvite,
      install.contractId,
      {
        cid: inviteContractId,
      },
      domainId,
      [Contract.toDisclosedContract(GroupInvite, groupInvite)]
    );
  }
  async joinGroup(
    user: string,
    provider: string,
    group: ContractId<Group>,
    inviteContractId: ContractId<AcceptedGroupInvite>,
    domainId: string
  ) {
    const install = await this.getSplitwellInstall(user, provider);
    await this.exercise(
      [user],
      [],
      SplitwellInstall.SplitwellInstall_Join,
      install.contractId,
      {
        group: group,
        cid: inviteContractId,
      },
      domainId
    );
  }

  async enterPayment(
    user: string,
    provider: string,
    group: ContractId<Group>,
    amount: string,
    description: string,
    domainId: string
  ) {
    const install = await this.getSplitwellInstall(user, provider);
    await this.exercise(
      [user],
      [],
      SplitwellInstall.SplitwellInstall_EnterPayment,
      install.contractId,
      {
        group: group,
        amount: amount,
        description: description,
      },
      domainId
    );
  }

  async initiateTransfer(
    sender: string,
    provider: string,
    group: ContractId<Group>,
    receiverAmounts: ReceiverCCAmount[],
    domainId: string
  ) {
    const install = await this.getSplitwellInstall(sender, provider);
    return await this.exercise(
      [sender],
      [],
      SplitwellInstall.SplitwellInstall_InitiateTransfer,
      install.contractId,
      {
        group: group,
        receiverAmounts: receiverAmounts,
      },
      domainId
    );
  }

  async getSplitwellInstall(user: string, provider: string) {
    const install = await this.querySplitwellInstall(user, provider);
    if (!install) {
      throw new Error('Could not find SplitwellInstall');
    }
    return install;
  }

  async querySplitwellInstall(user: string, provider: string) {
    const response = await this.queryAcs(user, SplitwellInstall);
    return response.find(c => c.payload.user === user && c.payload.provider === provider);
  }
}

export const [SplitwellLedgerApiClientProvider, useSplitwellLedgerApiClient] =
  buildLedgerApiClientInterface(SplitwellLedgerApiClient);
