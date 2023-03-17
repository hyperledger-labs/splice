import { ActiveContractsServicePromiseClient } from 'common-protobuf/com/daml/ledger/api/v1/active_contracts_service_grpc_web_pb';
import { GetActiveContractsRequest } from 'common-protobuf/com/daml/ledger/api/v1/active_contracts_service_pb';
import { UserManagementServicePromiseClient } from 'common-protobuf/com/daml/ledger/api/v1/admin/user_management_service_grpc_web_pb';
import {
  GetUserRequest,
  ListUserRightsRequest,
} from 'common-protobuf/com/daml/ledger/api/v1/admin/user_management_service_pb';
import { CommandServicePromiseClient } from 'common-protobuf/com/daml/ledger/api/v1/command_service_grpc_web_pb';
import {
  SubmitAndWaitForTransactionTreeResponse,
  SubmitAndWaitRequest,
} from 'common-protobuf/com/daml/ledger/api/v1/command_service_pb';
import {
  Command,
  Commands,
  CreateCommand,
  DisclosedContract,
  ExerciseCommand,
} from 'common-protobuf/com/daml/ledger/api/v1/commands_pb';
import { CreatedEvent } from 'common-protobuf/com/daml/ledger/api/v1/event_pb';
import {
  Filters,
  InclusiveFilters,
  TransactionFilter,
} from 'common-protobuf/com/daml/ledger/api/v1/transaction_filter_pb';
import { TransactionTree } from 'common-protobuf/com/daml/ledger/api/v1/transaction_pb';
import { Identifier, Value } from 'common-protobuf/com/daml/ledger/api/v1/value_pb';
import grpcWeb, { RpcError, StatusCode } from 'grpc-web';
import React, { useContext } from 'react';
import { v4 as uuidv4 } from 'uuid';

import { Choice, ContractId, Template } from '@daml/types';

import { Contract, decodeProtoMetadata } from '..';

const sleep = async (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

const retrySubmit = async <T,>(
  maxRetries: number,
  retryDelay: number,
  task: () => Promise<T>
): Promise<T> => {
  let retries = 0;
  let error;
  while (retries < maxRetries) {
    if (retries > 0) {
      console.debug('Retrying now...');
    }
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const response: { type: 'success'; value: T } | { type: 'retryable'; error: any } = await task()
      .then<{ type: 'success'; value: T }>(r => {
        return { type: 'success', value: r };
      })
      .catch(e => {
        const rpcError = e as RpcError;
        if (rpcError.code === StatusCode.NOT_FOUND) {
          return { type: 'retryable', error: e };
        } else {
          console.debug('Fatal error, aborting...');
          throw e;
        }
      });
    switch (response.type) {
      case 'retryable':
        console.debug(
          `Found retryable error ${JSON.stringify(error)}, retrying after ${retryDelay}ms...`
        );
        error = response.error;
        retries++;
        await sleep(retryDelay);
        break;
      case 'success':
        return response.value;
    }
  }
  console.debug('Exceeded retries, giving up...');
  throw error;
};

// Description of a command that we can log
type CommandDescription =
  | { type: 'create'; templateId: string; payload: unknown }
  | {
      type: 'exercise';
      templateId: string;
      choice: string;
      contractId: ContractId<unknown>;
      argument: unknown;
    };

// exported so it can be extended by apps
export class LedgerApiClient {
  activeContractsServiceClient: ActiveContractsServicePromiseClient;
  commandServiceClient: CommandServicePromiseClient;
  userManagementServiceClient: UserManagementServicePromiseClient;
  userId: string;
  metaData: grpcWeb.Metadata;

  constructor(
    activeContractsServiceClient: ActiveContractsServicePromiseClient,
    commandServiceClient: CommandServicePromiseClient,
    userManagementServiceClient: UserManagementServicePromiseClient,
    userId: string,
    token: string | undefined
  ) {
    this.activeContractsServiceClient = activeContractsServiceClient;
    this.commandServiceClient = commandServiceClient;
    this.userManagementServiceClient = userManagementServiceClient;
    this.userId = userId;
    this.metaData = token !== undefined ? { Authorization: 'Bearer ' + token } : {};
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
      metadata: decodeProtoMetadata(ev.getMetadata()!),
    };
  }

  async create<T extends object, K>(
    actAs: string[],
    template: Template<T, K>,
    payload: T,
    domainId: string | undefined = undefined,
    disclosedContracts: DisclosedContract[] = []
  ): Promise<Contract<T>> {
    const templateId = this.templateIdToIdentifier(template.templateId);
    const cmd = new Command().setCreate(
      new CreateCommand()
        .setTemplateId(templateId)
        .setCreateArguments(template.encodeProto(payload).getRecord())
    );
    const jsonCmd: CommandDescription = {
      type: 'create',
      templateId: template.templateId,
      payload: template.encode(payload),
    };
    const transaction = await this.submitCommand(
      actAs,
      [],
      cmd,
      jsonCmd,
      domainId,
      disclosedContracts
    );
    const createdEv = transaction
      .getEventsByIdMap()
      .get(transaction.getRootEventIdsList()[0])
      ?.getCreated()!;
    return this.decodeCreateEvent(template, createdEv);
  }

  async exercise<T extends object, C, R, K>(
    actAs: string[],
    readAs: string[],
    choice: Choice<T, C, R, K>,
    contractId: ContractId<T>,
    argument: C,
    domainId: string | undefined = undefined,
    disclosedContracts: DisclosedContract[] = []
  ): Promise<R> {
    const encodedArg = choice.argumentSerializable().encodeProto(argument);
    const templateId = this.templateIdToIdentifier(choice.template().templateId);
    const cmd = new Command().setExercise(
      new ExerciseCommand()
        .setTemplateId(templateId)
        .setChoice(choice.choiceName)
        .setContractId(contractId)
        .setChoiceArgument(encodedArg)
    );
    const jsonCmd: CommandDescription = {
      type: 'exercise',
      templateId: choice.template().templateId,
      choice: choice.choiceName,
      contractId: contractId,
      argument: choice.argumentEncode(argument),
    };
    const transaction = await this.submitCommand(
      actAs,
      readAs,
      cmd,
      jsonCmd,
      domainId,
      disclosedContracts
    );
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
    command: Command,
    jsonCommand: CommandDescription,
    domainId: string | undefined,
    disclosedContracts: DisclosedContract[] = []
  ): Promise<TransactionTree> {
    const maxRetries = 10;
    const retryDelay = 500;
    const commandId = uuidv4();
    const cmds = new Commands()
      .setCommandsList([command])
      .setActAsList(actAs)
      .setReadAsList(readAs)
      .setApplicationId(this.userId)
      .setCommandId(commandId)
      .setDisclosedContractsList(disclosedContracts);
    if (domainId) {
      cmds.setWorkflowId(`domain-id:${domainId}`);
    }
    const request = new SubmitAndWaitRequest().setCommands(cmds);
    const response: SubmitAndWaitForTransactionTreeResponse = await retrySubmit(
      maxRetries,
      retryDelay,
      async () => {
        console.debug(
          `Submitting command: actAs=${JSON.stringify(actAs)}, readAs=${JSON.stringify(
            readAs
          )}, commandId=${commandId}, domainId=${domainId}, cmd=${JSON.stringify(jsonCommand)}`
        );
        return await this.commandServiceClient
          .submitAndWaitForTransactionTree(request, this.metaData)
          .then(r => {
            console.debug(`Command with commandId=${commandId} succeeded`);
            return r;
          })
          .catch(e => {
            console.debug(`Command with commandId=${commandId} failed: ${JSON.stringify(e)}`);
            throw e;
          });
      }
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
    // TODO(tech-debt) Avoid relying on verbose mode. This needs changes in decoding of the protobuf values.
    const request = new GetActiveContractsRequest().setFilter(filter).setVerbose(true);
    const response = this.activeContractsServiceClient.getActiveContracts(request, this.metaData);
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

  async getUserReadAs(userId: string): Promise<string[]> {
    const userRightsResponse = await this.userManagementServiceClient.listUserRights(
      new ListUserRightsRequest().setUserId(this.userId),
      this.metaData
    );
    return userRightsResponse.getRightsList().flatMap(right => {
      const readAs = right.getCanReadAs();
      return readAs ? [readAs.getParty()] : [];
    });
  }

  async getPrimaryParty(): Promise<string> {
    const user = await this.userManagementServiceClient.getUser(
      new GetUserRequest().setUserId(this.userId),
      this.metaData
    );
    return user.getUser()!.getPrimaryParty();
  }
}

export interface LedgerApiClientProps {
  url: string;
  userId: string;
  token: string;
}

// use this to create a Provider component and a use function in case you extended LedgerApiClient
export const buildLedgerApiClientInterface = <T extends LedgerApiClient>(c: {
  new (
    activeContractsServiceClient: ActiveContractsServicePromiseClient,
    commandServiceClient: CommandServicePromiseClient,
    userManagementServiceClient: UserManagementServicePromiseClient,
    userId: string,
    token: string
  ): T;
}): [React.FC<React.PropsWithChildren<LedgerApiClientProps>>, () => T] => {
  const context = React.createContext<T | undefined>(undefined);

  const LedgerApiClientProvider: React.FC<React.PropsWithChildren<LedgerApiClientProps>> = ({
    children,
    url,
    userId,
    token,
  }) => {
    const activeContractsClient = new ActiveContractsServicePromiseClient(url);
    const commandServiceClient = new CommandServicePromiseClient(url);
    const userManagementClient = new UserManagementServicePromiseClient(url);
    const apiClient = new c(
      activeContractsClient,
      commandServiceClient,
      userManagementClient,
      userId,
      token
    );
    return <context.Provider value={apiClient}>{children}</context.Provider>;
  };

  const useLedgerApiClient: () => T = () => {
    const client = useContext(context);
    if (!client) {
      throw new Error('Ledger API client not initialized');
    }
    return client;
  };

  return [LedgerApiClientProvider, useLedgerApiClient];
};

export const [LedgerApiClientProvider, useLedgerApiClient] =
  buildLedgerApiClientInterface(LedgerApiClient);
