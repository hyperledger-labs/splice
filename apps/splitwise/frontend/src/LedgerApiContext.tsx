import React, { useContext } from 'react';
import { v4 as uuidv4 } from 'uuid';

import { CCUserHostedAt } from '@daml.js/canton-coin/lib/CC/Scripts/Util';
import {
  AcceptedGroupInvite,
  GroupInvite,
  SplitwiseInstall,
} from '@daml.js/splitwise/lib/CN/Splitwise';
import { AcceptedAppPayment } from '@daml.js/wallet/lib/CN/Wallet';
import { Identifier, Value } from '@daml/ledger-api';
import { Choice, ContractId, Template } from '@daml/types';

import { Contract } from './Contract';
import { ActiveContractsServiceClient } from './com/daml/ledger/api/v1/Active_contracts_serviceServiceClientPb';
import { CommandServiceClient } from './com/daml/ledger/api/v1/Command_serviceServiceClientPb';
import { GetActiveContractsRequest } from './com/daml/ledger/api/v1/active_contracts_service_pb';
import { UserManagementServiceClient } from './com/daml/ledger/api/v1/admin/User_management_serviceServiceClientPb';
import { GetUserRequest } from './com/daml/ledger/api/v1/admin/user_management_service_pb';
import { SubmitAndWaitRequest } from './com/daml/ledger/api/v1/command_service_pb';
import { Command, Commands, ExerciseByKeyCommand } from './com/daml/ledger/api/v1/commands_pb';
import {
  Filters,
  InclusiveFilters,
  TransactionFilter,
} from './com/daml/ledger/api/v1/transaction_filter_pb';
import { GroupKey } from './com/daml/network/splitwise/v0/splitwise_service_pb';

class LedgerApiClient {
  activeContractsServiceClient: ActiveContractsServiceClient;
  commandServiceClient: CommandServiceClient;
  userManagementServiceClient: UserManagementServiceClient;

  collectionDuration: string = (5 * 60 * 1000000).toString();
  acceptDuration: string = (5 * 60 * 1000000).toString();

  constructor(
    activeContractsServiceClient: ActiveContractsServiceClient,
    commandServiceClient: CommandServiceClient,
    userManagementServiceClient: UserManagementServiceClient
  ) {
    this.activeContractsServiceClient = activeContractsServiceClient;
    this.commandServiceClient = commandServiceClient;
    this.userManagementServiceClient = userManagementServiceClient;
  }

  async exerciseByKey<T extends object, C, R, K>(
    actAs: string[],
    readAs: string[],
    choice: Choice<T, C, R, K>,
    key: K,
    argument: C
  ): Promise<R> {
    const encodedKey = choice.template().keyEncodeProto(key);
    const encodedArg = choice.argumentSerializable.encodeProto(argument);
    const [packageId, moduleName, entityName] = choice.template().templateId.split(':');
    const templateId = new Identifier()
      .setPackageId(packageId)
      .setModuleName(moduleName)
      .setEntityName(entityName);
    const cmd = new Command().setExercisebykey(
      new ExerciseByKeyCommand()
        .setTemplateId(templateId)
        .setChoice(choice.choiceName)
        .setContractKey(encodedKey)
        .setChoiceArgument(encodedArg)
    );
    const cmds = new Commands()
      .setCommandsList([cmd])
      .setActAsList(actAs)
      .setReadAsList(readAs)
      // TODO(#661) pass along user id and use that here.
      .setApplicationId('splitwise-web-ui')
      .setCommandId(uuidv4());
    const request = new SubmitAndWaitRequest().setCommands(cmds);
    const response = await this.commandServiceClient.submitAndWaitForTransactionTree(request, null);
    const transaction = response.getTransaction()!;
    const exerciseEv = transaction
      .getEventsByIdMap()
      .get(transaction.getRootEventIdsList()[0])
      ?.getExercised()!;
    const exerciseResult = choice.resultSerializable.decodeProto(exerciseEv.getExerciseResult()!);
    return exerciseResult;
  }

  async queryAcs<T extends object, K, I extends string>(
    p: string,
    t: Template<T, K, I>
  ): Promise<Contract<T>[]> {
    const filter = new TransactionFilter();
    const [packageId, moduleName, entityName] = t.templateId.split(':');
    const templateId = new Identifier()
      .setPackageId(packageId)
      .setModuleName(moduleName)
      .setEntityName(entityName);
    filter
      .getFiltersByPartyMap()
      .set(p, new Filters().setInclusive(new InclusiveFilters().setTemplateIdsList([templateId])));
    // TODO(M1-92) Avoid relying on verbose mode. This needs changes in decoding of the protobuf values.
    const request = new GetActiveContractsRequest().setFilter(filter).setVerbose(true);
    const response = this.activeContractsServiceClient.getActiveContracts(request);
    const contracts = await new Promise<Contract<T>[]>(resolve => {
      let acc: Contract<T>[] = [];
      response.on('data', el => {
        const decoded: Contract<T>[] = el.getActiveContractsList().map(ev => ({
          contractId: ev.getContractId() as ContractId<T>,
          payload: t.decodeProto(new Value().setRecord(ev.getCreateArguments())),
        }));
        acc = acc.concat(decoded);
      });
      response.on('end', () => resolve(acc));
    });
    return contracts;
  }

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
    validator: string,
    key: GroupKey,
    acceptedPaymentContractId: ContractId<AcceptedAppPayment>
  ) {
    await this.exerciseByKey(
      [user],
      [validator],
      SplitwiseInstall.SplitwiseInstall_CompleteTransfer,
      { _1: user, _2: provider },
      {
        groupKey: {
          owner: key.getOwnerPartyId(),
          provider: key.getProviderPartyId(),
          id: { unpack: key.getId() },
        },
        acceptedPaymentCid: acceptedPaymentContractId,
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

  async getValidatorPartyId(user: string): Promise<string> {
    const contracts = await this.queryAcs(user, CCUserHostedAt);
    const r = contracts.find(c => c.payload.user === user)!;
    return r.payload.validator;
  }
  async getPrimaryParty(userId: string): Promise<string> {
    const user = await this.userManagementServiceClient.getUser(
      new GetUserRequest().setUserId(userId),
      null
    );
    return user.getUser()!.getPrimaryParty();
  }
}

const LedgerApiClientContext = React.createContext<LedgerApiClient | undefined>(undefined);

interface LedgerApiClientProps {
  url: string;
}

export const LedgerApiClientProvider: React.FC<React.PropsWithChildren<LedgerApiClientProps>> = ({
  children,
  url,
}) => {
  const acsClient = new ActiveContractsServiceClient(url);
  const commandClient = new CommandServiceClient(url);
  const userManagementClient = new UserManagementServiceClient(url);
  const splitwiseClient = new LedgerApiClient(acsClient, commandClient, userManagementClient);
  return (
    <LedgerApiClientContext.Provider value={splitwiseClient}>
      {children}
    </LedgerApiClientContext.Provider>
  );
};

export const useLedgerApiClient: () => LedgerApiClient = () => {
  const client = useContext(LedgerApiClientContext);
  if (!client) {
    throw new Error('Splitwise client not initialized');
  }
  return client;
};
