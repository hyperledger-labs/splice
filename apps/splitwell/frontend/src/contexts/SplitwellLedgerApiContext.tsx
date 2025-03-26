// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  LedgerApiClient,
  LedgerApiProps,
  useUserState,
  LedgerApiClientProvider,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { AssignedContract, Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import React, { useContext } from 'react';

import {
  AppPaymentRequest,
  ReceiverAmuletAmount,
} from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';
import {
  AcceptedGroupInvite,
  Group,
  GroupId,
  GroupInvite,
  SplitwellRules,
} from '@daml.js/splitwell/lib/Splice/Splitwell';
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
    dso: string,
    id: string,
    domainId: string,
    rules: Contract<SplitwellRules>
  ) {
    await this.exercise(
      [user],
      [],
      SplitwellRules.SplitwellRules_RequestGroup,
      rules.contractId,
      {
        group: {
          owner: user,
          provider: provider,
          dso: dso,
          id: { unpack: id },
          members: [],
          acceptDuration: { microseconds: this.acceptDuration },
        },
        user,
      },
      domainId,
      [Contract.toDisclosedContract(SplitwellRules, rules)]
    );
  }

  async createGroupInvite(
    user: string,
    _provider: string,
    groupId: GroupId,
    groups: AssignedContract<Group>[],
    domainId: string,
    rules: Contract<SplitwellRules>
  ) {
    const group = this.getGroup(groupId, groups);
    await this.exercise(
      [user],
      [],
      SplitwellRules.SplitwellRules_CreateInvite,
      rules.contractId,
      {
        group: group.contract.contractId,
        user,
      },
      domainId,
      [Contract.toDisclosedContract(SplitwellRules, rules)]
    );
  }

  async acceptInvite(
    user: string,
    _provider: string,
    inviteContractId: ContractId<GroupInvite>,
    domainId: string,
    rules: Contract<SplitwellRules>,
    groupInvite: Contract<GroupInvite>
  ) {
    await this.exercise(
      [user],
      [],
      SplitwellRules.SplitwellRules_AcceptInvite,
      rules.contractId,
      {
        cid: inviteContractId,
        user,
      },
      domainId,
      [
        Contract.toDisclosedContract(GroupInvite, groupInvite),
        Contract.toDisclosedContract(SplitwellRules, rules),
      ]
    );
  }
  async joinGroup(
    user: string,
    _provider: string,
    groupId: GroupId,
    groups: AssignedContract<Group>[],
    inviteContractId: ContractId<AcceptedGroupInvite>,
    domainId: string,
    rules: Contract<SplitwellRules>
  ) {
    const group = this.getGroup(groupId, groups);
    await this.exercise(
      [user],
      [],
      SplitwellRules.SplitwellRules_Join,
      rules.contractId,
      {
        group: group.contract.contractId,
        cid: inviteContractId,
        user,
      },
      domainId,
      [Contract.toDisclosedContract(SplitwellRules, rules)]
    );
  }

  async enterPayment(
    user: string,
    _provider: string,
    groupId: GroupId,
    groups: AssignedContract<Group>[],
    amount: string,
    description: string,
    domainId: string,
    rules: Contract<SplitwellRules>
  ) {
    const group = this.getGroup(groupId, groups);
    await this.exercise(
      [user],
      [],
      SplitwellRules.SplitwellRules_EnterPayment,
      rules.contractId,
      {
        group: group.contract.contractId,
        amount: amount,
        description: description,
        user,
      },
      domainId,
      [Contract.toDisclosedContract(SplitwellRules, rules)]
    );
  }

  async initiateTransfer(
    sender: string,
    _provider: string,
    groupId: GroupId,
    groups: AssignedContract<Group>[],
    receiverAmounts: ReceiverAmuletAmount[],
    domainId: string,
    rules: Contract<SplitwellRules>
  ): Promise<ContractId<AppPaymentRequest>> {
    const group = this.getGroup(groupId, groups);
    return (
      await this.exercise(
        [sender],
        [],
        SplitwellRules.SplitwellRules_InitiateTransfer,
        rules.contractId,
        {
          group: group.contract.contractId,
          receiverAmounts: receiverAmounts,
          user: sender,
        },
        domainId,
        [Contract.toDisclosedContract(SplitwellRules, rules)]
      )
    )._2;
  }

  async requestSplitwellInstall(user: string, domainId: string, rules: Contract<SplitwellRules>) {
    return await this.exercise(
      [user],
      [],
      SplitwellRules.SplitwellRules_RequestInstall,
      rules.contractId,
      { user },
      domainId,
      [Contract.toDisclosedContract(SplitwellRules, rules)]
    );
  }
}

const SplitwellLedgerApiContext = React.createContext<SplitwellLedgerApiClient | undefined>(
  undefined
);

export const SplitwellLedgerApiClientProvider: React.FC<
  React.PropsWithChildren<LedgerApiProps>
> = ({ jsonApiUrl, children, packageIdResolver }) => {
  const { userAccessToken, userId } = useUserState();

  let ledgerApiClient: SplitwellLedgerApiClient | undefined;

  if (userAccessToken && userId) {
    ledgerApiClient = new SplitwellLedgerApiClient(
      jsonApiUrl,
      userAccessToken,
      userId,
      packageIdResolver
    );
  }

  // We instantiate both the basic ledger api context and the one for splitwell so hooks like
  // usePrimaryParty that only rely on the basic one work as well
  return (
    <SplitwellLedgerApiContext.Provider value={ledgerApiClient}>
      <LedgerApiClientProvider jsonApiUrl={jsonApiUrl} packageIdResolver={packageIdResolver}>
        {children}
      </LedgerApiClientProvider>
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
