import { Contract } from 'common-frontend';
import { ContractMetadata } from 'directory-openapi';
import React, { useContext } from 'react';

import { DirectoryEntry, DirectoryInstall } from '@daml.js/directory/lib/CN/Directory';
import Ledger, { CreateEvent, LedgerOptions } from '@daml/ledger';
import { Choice, ContractId, Template } from '@daml/types';

// Uses the JSON API (via @daml/ledger) to connect to the ledger.
export class LedgerApiClient {
  private ledger: Ledger;
  private userId: string;
  constructor(ledger: Ledger, userId: string) {
    this.ledger = ledger;
    this.userId = userId;
  }
  async getPrimaryParty(): Promise<string> {
    const user = await this.ledger.getUser(this.userId);
    return user.primaryParty!;
  }

  async create<T extends object, K>(
    actAs: string[],
    template: Template<T, K>,
    payload: T
  ): Promise<Contract<T>> {
    console.debug(
      `Creating template templateId=${template.templateId}, actAs=${JSON.stringify(
        actAs
      )}, payload=${JSON.stringify(payload)}`
    );
    const response = await this.ledger
      .create(template, payload)
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
    argument: C
  ): Promise<R> {
    console.debug(
      `Exercising choice: actAs=${JSON.stringify(actAs)}, readAs=${JSON.stringify(
        readAs
      )}, choiceName=${choice.choiceName}, templateId=${
        choice.template().templateId
      }, contractId=${contractId}.`
    );
    const result = await this.ledger
      .exercise(choice, contractId, argument)
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

  async queryDirectoryInstall(
    user: string,
    provider: string
  ): Promise<Contract<DirectoryInstall> | undefined> {
    const response = await this.ledger.query(DirectoryInstall);
    return response
      .map(ev => this.toContract(ev))
      .find(c => c.payload.user === user && c.payload.provider === provider);
  }

  async queryOwnedDirectoryEntries(
    user: string,
    provider: string
  ): Promise<Contract<DirectoryEntry>[]> {
    // We query through our own participant here, so we get filtering to entries visible only to us.
    // Alternatively, we could add a filtered API on the provider.
    const response = await this.ledger.query(DirectoryEntry);
    return response
      .filter(c => c.payload.user === user && c.payload.provider === provider)
      .map(ev => this.toContract(ev));
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
  userId: string;
  token: string;
}

export const LedgerApiClientProvider: React.FC<React.PropsWithChildren<LedgerApiProps>> = ({
  jsonApiUrl,
  userId,
  token,
  children,
}) => {
  const ledgerOptions: LedgerOptions = { httpBaseUrl: jsonApiUrl, token: token };
  const ledgerApiClient = new LedgerApiClient(new Ledger(ledgerOptions), userId);

  return <LedgerApiContext.Provider value={ledgerApiClient}>{children}</LedgerApiContext.Provider>;
};

export const useLedgerApiClient: () => LedgerApiClient = () => {
  const client = useContext<LedgerApiClient | undefined>(LedgerApiContext);
  if (!client) {
    throw new Error('Directory client not initialized');
  }
  return client;
};
