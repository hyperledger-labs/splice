import { ActiveContractsServicePromiseClient } from 'common-protobuf/com/daml/ledger/api/v1/active_contracts_service_grpc_web_pb';
import { GetActiveContractsRequest } from 'common-protobuf/com/daml/ledger/api/v1/active_contracts_service_pb';
import { UserManagementServicePromiseClient } from 'common-protobuf/com/daml/ledger/api/v1/admin/user_management_service_grpc_web_pb';
import { GetUserRequest } from 'common-protobuf/com/daml/ledger/api/v1/admin/user_management_service_pb';
import { CommandServicePromiseClient } from 'common-protobuf/com/daml/ledger/api/v1/command_service_grpc_web_pb';
import { SubmitAndWaitRequest } from 'common-protobuf/com/daml/ledger/api/v1/command_service_pb';
import {
  Command,
  Commands,
  CreateCommand,
  ExerciseByKeyCommand,
} from 'common-protobuf/com/daml/ledger/api/v1/commands_pb';
import { CreatedEvent } from 'common-protobuf/com/daml/ledger/api/v1/event_pb';
import {
  Filters,
  InclusiveFilters,
  TransactionFilter,
} from 'common-protobuf/com/daml/ledger/api/v1/transaction_filter_pb';
import { TransactionTree } from 'common-protobuf/com/daml/ledger/api/v1/transaction_pb';
import { GroupKey } from 'common-protobuf/com/daml/network/splitwise/v0/splitwise_service_pb';
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

// TODO(#1172) Move shared code into common
class LedgerApiClient {
  ActiveContractsServicePromiseClient: ActiveContractsServicePromiseClient;
  CommandServicePromiseClient: CommandServicePromiseClient;
  UserManagementServicePromiseClient: UserManagementServicePromiseClient;
  userId: string;

  collectionDuration: string = (5 * 60 * 1000000).toString();
  acceptDuration: string = (5 * 60 * 1000000).toString();

  constructor(
    ActiveContractsServicePromiseClient: ActiveContractsServicePromiseClient,
    CommandServicePromiseClient: CommandServicePromiseClient,
    UserManagementServicePromiseClient: UserManagementServicePromiseClient,
    userId: string
  ) {
    this.ActiveContractsServicePromiseClient = ActiveContractsServicePromiseClient;
    this.CommandServicePromiseClient = CommandServicePromiseClient;
    this.UserManagementServicePromiseClient = UserManagementServicePromiseClient;
    this.userId = userId;
  }

  templateIdToIdentifier(templateId: string): Identifier {
    const [packageId, moduleName, entityName] = templateId.split(':');
    return new Identifier()
      .setPackageId(packageId)
      .setModuleName(moduleName)
      .setEntityName(entityName);
  }

  decodeCreateEvent<T extends object, K>(template: Template<T, K>, ev: CreatedEvent): Contract<T> {
    return {
      contractId: ev.getContractId() as ContractId<T>,
      payload: template.decodeProto(new Value().setRecord(ev.getCreateArguments())),
    };
  }

  async create<T extends object, K>(
    actAs: string[],
    template: Template<T, K>,
    payload: T
  ): Promise<Contract<T>> {
    const templateId = this.templateIdToIdentifier(template.templateId);
    const cmd = new Command().setCreate(
      new CreateCommand()
        .setTemplateId(templateId)
        .setCreateArguments(template.encodeProto(payload).getRecord())
    );
    const transaction = await this.submitCommand(actAs, [], cmd);
    const createdEv = transaction
      .getEventsByIdMap()
      .get(transaction.getRootEventIdsList()[0])
      ?.getCreated()!;
    return this.decodeCreateEvent(template, createdEv);
  }

  async exerciseByKey<T extends object, C, R, K>(
    actAs: string[],
    readAs: string[],
    choice: Choice<T, C, R, K>,
    key: K,
    argument: C
  ): Promise<R> {
    const encodedKey = choice.template().keyEncodeProto(key);
    const encodedArg = choice.argumentSerializable().encodeProto(argument);
    const templateId = this.templateIdToIdentifier(choice.template().templateId);
    const cmd = new Command().setExercisebykey(
      new ExerciseByKeyCommand()
        .setTemplateId(templateId)
        .setChoice(choice.choiceName)
        .setContractKey(encodedKey)
        .setChoiceArgument(encodedArg)
    );
    const transaction = await this.submitCommand(actAs, readAs, cmd);
    const exerciseEv = transaction
      .getEventsByIdMap()
      .get(transaction.getRootEventIdsList()[0])
      ?.getExercised()!;
    const exerciseResult = choice.resultSerializable().decodeProto(exerciseEv.getExerciseResult()!);
    return exerciseResult;
  }

  async submitCommand(
    actAs: string[],
    readAs: string[],
    command: Command
  ): Promise<TransactionTree> {
    const cmds = new Commands()
      .setCommandsList([command])
      .setActAsList(actAs)
      .setReadAsList(readAs)
      .setApplicationId(this.userId)
      .setCommandId(uuidv4());
    const request = new SubmitAndWaitRequest().setCommands(cmds);
    const response = await this.CommandServicePromiseClient.submitAndWaitForTransactionTree(
      request,
      undefined
    );
    return response.getTransaction()!;
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
    const response = this.ActiveContractsServicePromiseClient.getActiveContracts(request);
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

  async querySplitwiseInstall(user: string, provider: string) {
    const response = await this.queryAcs(user, SplitwiseInstall);
    return response.find(c => c.payload.user === user && c.payload.provider === provider);
  }

  async getValidatorPartyId(user: string): Promise<string> {
    const contracts = await this.queryAcs(user, CCUserHostedAt);
    const r = contracts.find(c => c.payload.user === user)!;
    return r.payload.validator;
  }
  async getPrimaryParty(userId: string): Promise<string> {
    const user = await this.UserManagementServicePromiseClient.getUser(
      new GetUserRequest().setUserId(userId),
      undefined
    );
    return user.getUser()!.getPrimaryParty();
  }
}

const LedgerApiClientContext = React.createContext<LedgerApiClient | undefined>(undefined);

interface LedgerApiClientProps {
  url: string;
  userId: string;
}

export const LedgerApiClientProvider: React.FC<React.PropsWithChildren<LedgerApiClientProps>> = ({
  children,
  url,
  userId,
}) => {
  const acsClient = new ActiveContractsServicePromiseClient(url);
  const commandClient = new CommandServicePromiseClient(url);
  const userManagementClient = new UserManagementServicePromiseClient(url);
  const splitwiseClient = new LedgerApiClient(
    acsClient,
    commandClient,
    userManagementClient,
    userId
  );
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
