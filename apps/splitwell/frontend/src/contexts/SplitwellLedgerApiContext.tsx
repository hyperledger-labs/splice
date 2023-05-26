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
  acceptDuration: string = (5 * 60 * 1000000).toString();

  async requestGroup(
    user: string,
    provider: string,
    svc: string,
    id: string,
    domainId: string,
    install: ContractId<SplitwellInstall>
  ) {
    await this.exercise(
      [user],
      [],
      SplitwellInstall.SplitwellInstall_RequestGroup,
      install,
      {
        group: {
          owner: user,
          provider: provider,
          svc: svc,
          id: { unpack: id },
          members: [],
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
    domainId: string,
    install: ContractId<SplitwellInstall>
  ) {
    await this.exercise(
      [user],
      [],
      SplitwellInstall.SplitwellInstall_CreateInvite,
      install,
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
    install: ContractId<SplitwellInstall>,
    groupInvite: Contract<GroupInvite>
  ) {
    await this.exercise(
      [user],
      [],
      SplitwellInstall.SplitwellInstall_AcceptInvite,
      install,
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
    domainId: string,
    install: ContractId<SplitwellInstall>
  ) {
    await this.exercise(
      [user],
      [],
      SplitwellInstall.SplitwellInstall_Join,
      install,
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
    domainId: string,
    install: ContractId<SplitwellInstall>
  ) {
    await this.exercise(
      [user],
      [],
      SplitwellInstall.SplitwellInstall_EnterPayment,
      install,
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
    domainId: string,
    install: ContractId<SplitwellInstall>
  ) {
    return await this.exercise(
      [sender],
      [],
      SplitwellInstall.SplitwellInstall_InitiateTransfer,
      install,
      {
        group: group,
        receiverAmounts: receiverAmounts,
      },
      domainId
    );
  }
}

export const [SplitwellLedgerApiClientProvider, useSplitwellLedgerApiClient] =
  buildLedgerApiClientInterface(SplitwellLedgerApiClient);
