import {
  LedgerApiClient,
  Contract,
  AssignedContract,
  LedgerApiProps,
  useUserState,
  LedgerApiClientProvider,
} from 'common-frontend';
import React, { useContext } from 'react';

import {
  AcceptedGroupInvite,
  Group,
  GroupId,
  GroupInvite,
  SplitwellInstall,
} from '@daml.js/splitwell/lib/CN/Splitwell';
import { ReceiverCCAmount } from '@daml.js/wallet-payments/lib/CN/Wallet/Payment';
import Ledger, { LedgerOptions } from '@daml/ledger';
import { ContractId } from '@daml/types';

class SplitwellLedgerApiClient extends LedgerApiClient {
  acceptDuration: string = (5 * 60 * 1000000).toString();

  private getGroup(id: GroupId, groups: AssignedContract<Group>[]): AssignedContract<Group> {
    console.log(JSON.stringify(groups));
    const group = groups.find(c => c.contract.payload.id.unpack === id.unpack);
    if (!group) {
      throw new Error(`Group ${id} does not exist`);
    }
    return group;
  }

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
    groupId: GroupId,
    groups: AssignedContract<Group>[],
    domainId: string,
    install: ContractId<SplitwellInstall>
  ) {
    const group = this.getGroup(groupId, groups);
    await this.exercise(
      [user],
      [],
      SplitwellInstall.SplitwellInstall_CreateInvite,
      install,
      {
        group: group.contract.contractId,
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
    groupId: GroupId,
    groups: AssignedContract<Group>[],
    inviteContractId: ContractId<AcceptedGroupInvite>,
    domainId: string,
    install: ContractId<SplitwellInstall>
  ) {
    const group = this.getGroup(groupId, groups);
    await this.exercise(
      [user],
      [],
      SplitwellInstall.SplitwellInstall_Join,
      install,
      {
        group: group.contract.contractId,
        cid: inviteContractId,
      },
      domainId
    );
  }

  async enterPayment(
    user: string,
    provider: string,
    groupId: GroupId,
    groups: AssignedContract<Group>[],
    amount: string,
    description: string,
    domainId: string,
    install: ContractId<SplitwellInstall>
  ) {
    const group = this.getGroup(groupId, groups);
    await this.exercise(
      [user],
      [],
      SplitwellInstall.SplitwellInstall_EnterPayment,
      install,
      {
        group: group.contract.contractId,
        amount: amount,
        description: description,
      },
      domainId
    );
  }

  async initiateTransfer(
    sender: string,
    provider: string,
    groupId: GroupId,
    groups: AssignedContract<Group>[],
    receiverAmounts: ReceiverCCAmount[],
    domainId: string,
    install: ContractId<SplitwellInstall>
  ) {
    const group = this.getGroup(groupId, groups);
    return await this.exercise(
      [sender],
      [],
      SplitwellInstall.SplitwellInstall_InitiateTransfer,
      install,
      {
        group: group.contract.contractId,
        receiverAmounts: receiverAmounts,
      },
      domainId
    );
  }
}

const SplitwellLedgerApiContext = React.createContext<SplitwellLedgerApiClient | undefined>(
  undefined
);

export const SplitwellLedgerApiClientProvider: React.FC<
  React.PropsWithChildren<LedgerApiProps>
> = ({ jsonApiUrl, children }) => {
  const { userAccessToken, userId } = useUserState();

  let ledgerApiClient: SplitwellLedgerApiClient | undefined;

  if (userAccessToken && userId) {
    const ledgerOptions: LedgerOptions = { httpBaseUrl: jsonApiUrl, token: userAccessToken };
    ledgerApiClient = new SplitwellLedgerApiClient(new Ledger(ledgerOptions), userId);
  }

  // We instantiate both the basic ledger api context and the one for splitwell so hooks like
  // usePrimaryParty that only rely on the basic one work as well
  return (
    <SplitwellLedgerApiContext.Provider value={ledgerApiClient}>
      <LedgerApiClientProvider jsonApiUrl={jsonApiUrl}>{children}</LedgerApiClientProvider>
    </SplitwellLedgerApiContext.Provider>
  );
};

export const useSplitwellLedgerApiClient: () => SplitwellLedgerApiClient = () => {
  const client = useContext(SplitwellLedgerApiContext);
  if (!client) {
    throw new Error('Ledger API client is not initialized');
  }
  return client;
};
