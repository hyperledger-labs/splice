// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useUserState } from '@lfdecentralizedtrust/splice-common-frontend';
import { callWithLogging } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import React, { useContext } from 'react';
import { v4 as uuidv4 } from 'uuid';

import { DisclosedContract } from '@daml/ledger';
import { Choice, ContractId, Template, TemplateOrInterface } from '@daml/types';

const ANS_LEDGER_NAME = 'ans-ledger';

interface JsonApiErrorBody {
  error: string;
}

interface JsonApiErrorResponse {
  status: number;
  statusText: string;
  body: JsonApiErrorBody;
}

export class JsonApiError extends Error {
  status: number;
  statusText: string;
  body: JsonApiErrorBody;

  constructor({ status, body, statusText }: JsonApiErrorResponse) {
    super(statusText);
    this.name = 'JsonApiError';
    this.status = status;
    this.statusText = statusText;
    this.body = body;
  }
}

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
        if (response.ok) {
          const responseBody = await response.json();
          return responseBody.user;
        } else {
          const responseBody = await response.text();
          throw new Error(
            `getPrimaryParty: HTTP ${response.status} ${response.statusText}: ${responseBody}`
          );
        }
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
        templateId: choice.template().templateId,
        contractId: contractId,
        choice: choice.choiceName,
        choiceArgument: choice.argumentEncode(argument),
      },
    };

    const body = {
      commands: [command],
      workflowId: '',
      commandId: uuidv4(),
      deduplicationPeriod: { Empty: {} },
      actAs: actAs,
      readAs: readAs,
      submissionId: '',
      disclosedContracts: disclosedContracts.map(c => ({
        contractId: c.contractId,
        createdEventBlob: c.createdEventBlob,
        synchronizerId: '',
        templateId: c.templateId,
      })),
      synchronizerId: domainId || '',
      packageIdSelectionPreference: [],
    };

    const describeChoice = `Exercised choice: actAs=${JSON.stringify(
      actAs
    )}, readAs=${JSON.stringify(readAs)}, choiceName=${choice.choiceName}, templateId=${
      choice.template().templateId
    }, contractId=${contractId}`;

    const responseBody = await fetch(
      `${this.jsonApiUrl}v2/commands/submit-and-wait-for-transaction-tree`,
      { headers: this.headers, method: 'POST', body: JSON.stringify(body) }
    )
      .then(async r => {
        if (r.ok) {
          console.debug(`${describeChoice} succeeded.`);
          return r.json();
        } else {
          const body = await r.text();
          throw new JsonApiError({
            status: r.status,
            body: { error: body },
            statusText: r.statusText,
          });
        }
      })
      .catch(e => {
        if (e instanceof JsonApiError) {
          console.debug(
            `${describeChoice} failed with status ${e.status}: ${e.statusText} and body: ${e.body}`
          );
        } else {
          console.debug(`${describeChoice} failed: ${JSON.stringify(e)}`);
        }
        throw e;
      });

    const tree = responseBody.transactionTree;
    const eventIds = Object.keys(tree.eventsById).map(Number);
    const rootEventId = Math.min(...eventIds);
    const rootEvent = tree.eventsById[rootEventId];
    const exerciseResult = choice.resultDecoder.runWithException(
      rootEvent.ExercisedTreeEvent.value.exerciseResult
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
