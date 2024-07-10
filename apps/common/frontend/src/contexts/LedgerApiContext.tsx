// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useUserState } from 'common-frontend';
import { Contract, callWithLogging } from 'common-frontend-utils';
import React, { useContext } from 'react';

import Ledger, { CommandMeta, CreateEvent, DisclosedContract, LedgerOptions } from '@daml/ledger';
import { Choice, ContractId, Template, TemplateOrInterface } from '@daml/types';

const ANS_LEDGER_NAME = 'ans-ledger';

export abstract class PackageIdResolver {
  protected getQualifiedName(templateId: string): string {
    const parts = templateId.split(':');
    if (parts.length !== 3) {
      throw Error(`Invalid template id: ${templateId}, expected 3 parts but got ${parts.length}`);
    }
    return `${parts[1]}:${parts[2]}`;
  }
  abstract resolveTemplateId(templateId: string): Promise<string>;

  async resolveTemplateOrInterface<T extends object, K>(
    tmpl: TemplateOrInterface<T, K>
  ): Promise<TemplateOrInterface<T, K>> {
    const templateId = await this.resolveTemplateId(tmpl.templateId);
    return { ...tmpl, templateId };
  }

  async resolveTemplate<T extends object, K>(tmpl: Template<T, K>): Promise<Template<T, K>> {
    const resolvedTmpl = await this.resolveTemplateOrInterface(tmpl);
    return resolvedTmpl as Template<T, K>;
  }

  async resolveChoice<T extends object, C, R, K>(
    choice: Choice<T, C, R, K>
  ): Promise<Choice<T, C, R, K>> {
    const template = await this.resolveTemplateOrInterface(choice.template());
    return { ...choice, template: () => template };
  }
}

// Uses the JSON API (via @daml/ledger) to connect to the ledger.
export class LedgerApiClient {
  private ledger: Ledger;
  private userId: string;
  private packageIdResolver: PackageIdResolver;
  constructor(ledger: Ledger, userId: string, packageIdResolver: PackageIdResolver) {
    this.ledger = ledger;
    this.userId = userId;
    this.packageIdResolver = packageIdResolver;
  }
  async getPrimaryParty(): Promise<string> {
    const user = await callWithLogging(
      ANS_LEDGER_NAME,
      'getUser',
      userId => this.ledger.getUser(userId),
      this.userId
    );
    return user.primaryParty!;
  }

  async create<T extends object, K>(
    actAs: string[],
    unresolvedTemplate: Template<T, K>,
    payload: T,
    domainId?: string
  ): Promise<Contract<T>> {
    const template = await this.packageIdResolver.resolveTemplate(unresolvedTemplate);
    console.debug(
      `Creating template templateId=${template.templateId}, actAs=${JSON.stringify(
        actAs
      )}, payload=${JSON.stringify(payload)}`
    );
    const meta: CommandMeta = {
      domainId,
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
    unresolvedChoice: Choice<T, C, R, K>,
    contractId: ContractId<T>,
    argument: C,
    domainId?: string,
    disclosedContracts: DisclosedContract[] = []
  ): Promise<R> {
    const choice = await this.packageIdResolver.resolveChoice(unresolvedChoice);
    console.debug(
      `Exercising choice: actAs=${JSON.stringify(actAs)}, readAs=${JSON.stringify(
        readAs
      )}, choiceName=${choice.choiceName}, templateId=${
        choice.template().templateId
      }, contractId=${contractId}.`
    );
    const meta: CommandMeta = {
      domainId,
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

  toContract<T extends object, K, I extends string = string>(
    ev: CreateEvent<T, K, I>
  ): Contract<T> {
    return {
      templateId: ev.templateId,
      contractId: ev.contractId,
      payload: ev.payload,
      // For now, we set dummy values here because the JSON API does not
      // yet expose this properly.
      createdEventBlob: '',
      createdAt: '',
    };
  }
}

const LedgerApiContext = React.createContext<LedgerApiClient | undefined>(undefined);

export interface LedgerApiProps {
  jsonApiUrl: string;
  packageIdResolver: PackageIdResolver;
}

export const LedgerApiClientProvider: React.FC<React.PropsWithChildren<LedgerApiProps>> = ({
  jsonApiUrl,
  packageIdResolver,
  children,
}) => {
  const { userAccessToken, userId } = useUserState();

  let ledgerApiClient: LedgerApiClient | undefined;

  if (userAccessToken && userId) {
    const ledgerOptions: LedgerOptions = { httpBaseUrl: jsonApiUrl, token: userAccessToken };
    ledgerApiClient = new LedgerApiClient(new Ledger(ledgerOptions), userId, packageIdResolver);
  }

  return <LedgerApiContext.Provider value={ledgerApiClient}>{children}</LedgerApiContext.Provider>;
};

export const useLedgerApiClient: () => LedgerApiClient | undefined = () => {
  return useContext(LedgerApiContext);
};
