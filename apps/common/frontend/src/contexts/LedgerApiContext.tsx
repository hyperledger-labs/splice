// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useUserState } from 'common-frontend';
import { callWithLogging } from 'common-frontend-utils';
import React, { useContext } from 'react';
import { v4 as uuidv4 } from 'uuid';

import { DisclosedContract } from '@daml/ledger';
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
  private jsonApiUrl: string;
  private userId: string;
  private packageIdResolver: PackageIdResolver;
  private headers: Headers;
  constructor(
    jsonApiUrl: string,
    token: string,
    userId: string,
    packageIdResolver: PackageIdResolver
  ) {
    this.jsonApiUrl = jsonApiUrl;
    this.headers = new Headers();
    this.headers.append('content-type', 'application/json');
    this.headers.append('Authorization', `Bearer ${token}`);
    this.userId = userId;
    this.packageIdResolver = packageIdResolver;
  }
  async getPrimaryParty(): Promise<string> {
    const user = await callWithLogging(
      ANS_LEDGER_NAME,
      'getUser',
      async userId => {
        const response = await fetch(`${this.jsonApiUrl}v2/users/${encodeURIComponent(userId)}`, {
          headers: this.headers,
        });
        const responseBody = await response.json();
        return responseBody.user;
      },
      this.userId
    );
    return user.primaryParty!;
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
    const command = {
      ExerciseCommand: {
        template_id: choice.template().templateId,
        contract_id: contractId,
        choice: choice.choiceName,
        choice_argument: choice.argumentEncode(argument),
      },
    };

    const body = {
      commands: [command],
      workflow_id: '',
      application_id: '',
      command_id: uuidv4(),
      deduplication_period: { Empty: {} },
      act_as: actAs,
      read_as: readAs,
      submission_id: '',
      disclosed_contracts: disclosedContracts.map(c => ({
        contractId: c.contractId,
        createdEventBlob: c.createdEventBlob,
        domainId: '',
        templateId: c.templateId,
      })),
      domain_id: domainId || '',
      package_id_selection_preference: [],
    };

    const responseBody = await fetch(
      `${this.jsonApiUrl}v2/commands/submit-and-wait-for-transaction-tree`,
      { headers: this.headers, method: 'POST', body: JSON.stringify(body) }
    )
      .then(r => {
        console.debug(
          `Exercised choice: actAs=${JSON.stringify(actAs)}, readAs=${JSON.stringify(
            readAs
          )}, choiceName=${choice.choiceName}, templateId=${
            choice.template().templateId
          }, contractId=${contractId} succeeded.`
        );
        return r.json();
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

    const tree = responseBody.transaction_tree;
    const rootEvent = tree.events_by_id[tree.root_event_ids[0]];
    const exerciseResult = choice.resultDecoder.runWithException(
      rootEvent.ExercisedTreeEvent.exercise_result
    );
    return exerciseResult;
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
    ledgerApiClient = new LedgerApiClient(jsonApiUrl, userAccessToken, userId, packageIdResolver);
  }

  return <LedgerApiContext.Provider value={ledgerApiClient}>{children}</LedgerApiContext.Provider>;
};

export const useLedgerApiClient: () => LedgerApiClient | undefined = () => {
  return useContext(LedgerApiContext);
};
