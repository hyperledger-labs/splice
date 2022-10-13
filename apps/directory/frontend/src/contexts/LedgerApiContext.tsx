import { Contract } from 'common-frontend';
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
import { Identifier, Value } from 'common-protobuf/com/daml/ledger/api/v1/value_pb';
import React, { useContext } from 'react';
import { v4 as uuidv4 } from 'uuid';

import { DirectoryEntry, DirectoryInstall } from '@daml.js/directory/lib/CN/Directory';
import { Choice, ContractId, Template } from '@daml/types';

class LedgerApiClient {
  activeContractsServiceClient: ActiveContractsServicePromiseClient;
  commandServiceClient: CommandServicePromiseClient;
  userManagementServiceClient: UserManagementServicePromiseClient;
  userId: string;

  collectionDuration: string = (5 * 60 * 1000000).toString();
  acceptDuration: string = (5 * 60 * 1000000).toString();

  constructor(
    activeContractsServiceClient: ActiveContractsServicePromiseClient,
    commandServiceClient: CommandServicePromiseClient,
    userManagementServiceClient: UserManagementServicePromiseClient,
    userId: string
  ) {
    this.activeContractsServiceClient = activeContractsServiceClient;
    this.commandServiceClient = commandServiceClient;
    this.userManagementServiceClient = userManagementServiceClient;
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
    const response = await this.commandServiceClient.submitAndWaitForTransactionTree(
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
    const response = this.activeContractsServiceClient.getActiveContracts(request);
    const contracts = await new Promise<Contract<T>[]>(resolve => {
      let acc: Contract<T>[] = [];
      response.on('data', el => {
        const decoded: Contract<T>[] = el
          .getActiveContractsList()
          .map(ev => this.decodeCreateEvent(t, ev));
        acc = acc.concat(decoded);
      });
      response.on('error', err => {
        console.error(`ACS stream for ${t.templateId} failed: ${err}`);
      });
      response.on('end', () => {
        resolve(acc);
      });
    });
    return contracts;
  }

  async queryDirectoryInstall(user: string, provider: string) {
    const response = await this.queryAcs(user, DirectoryInstall);
    return response.find(c => c.payload.user === user && c.payload.provider === provider);
  }

  async queryOwnedDirectoryEntries(user: string, provider: string) {
    // We query through our own participant here so we get filtering to entries visible only to us.
    // Alternatively, we could add a filtered API on the provider.
    const response = await this.queryAcs(user, DirectoryEntry);
    return response.filter(c => c.payload.user === user && c.payload.provider === provider);
  }

  async getPrimaryParty(): Promise<string> {
    const user = await this.userManagementServiceClient.getUser(
      new GetUserRequest().setUserId(this.userId),
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
  const activeContractsClient = new ActiveContractsServicePromiseClient(url);
  const commandServiceClient = new CommandServicePromiseClient(url);
  const userManagementClient = new UserManagementServicePromiseClient(url);
  const splitwiseClient = new LedgerApiClient(
    activeContractsClient,
    commandServiceClient,
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
    throw new Error('Ledger API client not initialized');
  }
  return client;
};
