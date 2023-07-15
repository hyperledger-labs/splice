import { callWithLogging, Contract, useUserState } from 'common-frontend';
import { ContractMetadata } from 'directory-openapi';
import React, { useContext } from 'react';

import Ledger, { CommandMeta, CreateEvent, DisclosedContract, LedgerOptions } from '@daml/ledger';
import { Query } from '@daml/ledger';
import { Choice, ContractId, Template, TemplateOrInterface } from '@daml/types';

const DIRECTORY_LEDGER_NAME = 'directory-ledger';

// Uses the JSON API (via @daml/ledger) to connect to the ledger.
export class LedgerApiClient {
  private ledger: Ledger;
  private userId: string;
  constructor(ledger: Ledger, userId: string) {
    this.ledger = ledger;
    this.userId = userId;
  }
  async getPrimaryParty(): Promise<string> {
    const user = await callWithLogging(
      DIRECTORY_LEDGER_NAME,
      'getUser',
      userId => this.ledger.getUser(userId),
      this.userId
    );
    return user.primaryParty!;
  }

  async create<T extends object, K>(
    actAs: string[],
    template: Template<T, K>,
    payload: T,
    domainId?: string
  ): Promise<Contract<T>> {
    console.debug(
      `Creating template templateId=${template.templateId}, actAs=${JSON.stringify(
        actAs
      )}, payload=${JSON.stringify(payload)}`
    );
    const meta: CommandMeta = {
      workflowId: domainId ? `domain-id:${domainId}` : undefined,
    };
    const response = await this.ledger
      .create(template, payload, meta)
      .then(r => {
        console.debug(
          `Create template: actAs=${JSON.stringify(actAs)}, templateId=${
            template.templateId
          } succeeded, contractId=${r.contractId}`
        );
        return r;
      })
      .catch(e => {
        console.debug(
          `Create template: actAs=${JSON.stringify(actAs)}, templateId=${
            template.templateId
          } failed: ${JSON.stringify(e)}`
        );
        throw e;
      });
    return this.toContract(response);
  }
  async exercise<T extends object, C, R, K>(
    actAs: string[],
    readAs: string[],
    choice: Choice<T, C, R, K>,
    contractId: ContractId<T>,
    argument: C,
    domainId?: string,
    disclosedContracts: DisclosedContract[] = []
  ): Promise<R> {
    console.debug(
      `Exercising choice: actAs=${JSON.stringify(actAs)}, readAs=${JSON.stringify(
        readAs
      )}, choiceName=${choice.choiceName}, templateId=${
        choice.template().templateId
      }, contractId=${contractId}.`
    );
    const meta: CommandMeta = {
      workflowId: domainId ? `domain-id:${domainId}` : undefined,
      disclosedContracts,
    };
    const result = await this.ledger
      .exercise(choice, contractId, argument, meta)
      .then(r => {
        console.debug(
          `Exercised choice: actAs=${JSON.stringify(actAs)}, readAs=${JSON.stringify(
            readAs
          )}, choiceName=${choice.choiceName}, templateId=${
            choice.template().templateId
          }, contractId=${contractId} succeeded.`
        );
        return r;
      })
      .catch(e => {
        console.debug(
          `Exercised choice: actAs=${JSON.stringify(actAs)}, readAs=${JSON.stringify(
            readAs
          )}, choiceName=${choice.choiceName}, templateId=${
            choice.template().templateId
          }, contractId=${contractId} failed: ${JSON.stringify(e)}`
        );
        throw e;
      });
    return result[0];
  }

  async query<T extends object, K, I extends string>(
    operationName: string,
    template: TemplateOrInterface<T, K, I>,
    query?: Query<T>
  ): Promise<CreateEvent<T, K, I>[]> {
    return await callWithLogging(
      DIRECTORY_LEDGER_NAME,
      operationName,
      (t, q) => this.ledger.query(t, q),
      template,
      query
    );
  }

  toContract<T extends object, K, I extends string = string>(
    ev: CreateEvent<T, K, I>
  ): Contract<T> {
    return {
      contractId: ev.contractId,
      payload: ev.payload,
      metadata: new ContractMetadata(),
    };
  }
}

const LedgerApiContext = React.createContext<LedgerApiClient | undefined>(undefined);

export interface LedgerApiProps {
  jsonApiUrl: string;
}

export const LedgerApiClientProvider: React.FC<React.PropsWithChildren<LedgerApiProps>> = ({
  jsonApiUrl,
  children,
}) => {
  const { userAccessToken, userId } = useUserState();

  let ledgerApiClient: LedgerApiClient | undefined;

  if (userAccessToken && userId) {
    const ledgerOptions: LedgerOptions = { httpBaseUrl: jsonApiUrl, token: userAccessToken };
    ledgerApiClient = new LedgerApiClient(new Ledger(ledgerOptions), userId);
  }

  return <LedgerApiContext.Provider value={ledgerApiClient}>{children}</LedgerApiContext.Provider>;
};

export const useLedgerApiClient: () => LedgerApiClient | undefined = () => {
  return useContext(LedgerApiContext);
};
