// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Command,
  createConfiguration,
  CreatedEvent,
  DeduplicationPeriod2,
  DefaultApi,
  DisclosedContract,
  Filters,
  GenerateExternalPartyTopologyResponse,
  GetActiveContractsRequest,
  GrantUserRightsResponse,
  HttpAuthAuthentication,
  IdentifierFilter,
  Kind,
  RequestContext,
  ResponseContext,
  ServerConfiguration,
  Signature,
  SignedTransaction,
  SigningPublicKey,
} from "@lfdecentralizedtrust/canton-json-api-v2-openapi";
import { AsyncLocalStorage } from "node:async_hooks";
import * as crypto from "node:crypto";
import { logger } from "./logger.js";

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export class LedgerApiClient {
  private api: DefaultApi;
  private als: AsyncLocalStorage<{ url: string | undefined }>;
  constructor(url: string, token: string) {
    this.als = new AsyncLocalStorage<{ url: string }>();
    this.api = new DefaultApi(
      createConfiguration({
        baseServer: new ServerConfiguration(url, {}),
        authMethods: {
          default: new HttpAuthAuthentication({
            getToken(): Promise<string> | string {
              return Promise.resolve(token);
            },
          }),
        },
        promiseMiddleware: [
          {
            post: async (context: ResponseContext) => {
              const url = this.als.getStore()?.url || "<unknown>";
              logger.debug(`[Response] ${url} ${context.httpStatusCode}`);
              return context;
            },
            pre: async (context: RequestContext) => {
              logger.debug(`[Request] ${context.getUrl()}`);
              const store = this.als.getStore();
              if (store) {
                store.url = context.getUrl();
              }
              return context;
            },
          },
        ],
      }),
    );
  }

  async generateExternalPartyTopology(
    synchronizer: string,
    partyHint: string,
    publicKey: SigningPublicKey,
  ): Promise<GenerateExternalPartyTopologyResponse> {
    return this.retry("generate external party topology", () =>
      this.als.run({ url: undefined }, () =>
        this.api.postV2PartiesExternalGenerateTopology({
          synchronizer,
          partyHint,
          localParticipantObservationOnly: false,
          confirmationThreshold: 1,
          publicKey,
        }),
      ),
    );
  }

  async allocateExternalParty(
    partyId: string,
    synchronizer: string,
    onboardingTransactions: SignedTransaction[],
    multiHashSignatures: Signature[],
  ): Promise<void> {
    return this.retry(
      `allocate external party ${partyId}`,
      () =>
        this.als.run({ url: undefined }, async () => {
          const response = await this.api.getV2PartiesParty(partyId);
          if (response?.partyDetails?.length === 0) {
            await this.api.postV2PartiesExternalAllocate({
              synchronizer,
              identityProviderId: "",
              onboardingTransactions,
              multiHashSignatures,
            });
          } else {
            logger.info(`Party id ${partyId} is already allocated`);
          }
        }),
      120, // party allocations take forever so we also retry forever aka 2min
    );
  }

  async submitTransaction(
    description: string,
    synchronizerId: string,
    actAs: string,
    privateKey: string,
    disclosedContracts: DisclosedContract[],
    command: Command,
  ): Promise<void> {
    const preparedTransaction = await this.retry(`prepare ${description}`, () =>
      this.als.run({ url: undefined }, () =>
        this.api.postV2InteractiveSubmissionPrepare({
          userId: "",
          actAs: [actAs],
          readAs: [],
          disclosedContracts,
          commandId: crypto.randomUUID(),
          synchronizerId,
          verboseHashing: false,
          packageIdSelectionPreference: [],
          commands: [command],
        }),
      ),
    );
    const deduplicationPeriod = new DeduplicationPeriod2();
    deduplicationPeriod.Empty = {};
    const signature = crypto.sign(
      null,
      Buffer.from(preparedTransaction.preparedTransactionHash, "base64"),
      privateKey,
    );

    return this.retry(`execute ${description}`, () =>
      this.als.run({ url: undefined }, () =>
        this.api.postV2InteractiveSubmissionExecute({
          deduplicationPeriod,
          submissionId: crypto.randomUUID(),
          userId: "",
          hashingSchemeVersion: preparedTransaction.hashingSchemeVersion,
          preparedTransaction: preparedTransaction.preparedTransaction,
          partySignatures: {
            signatures: [
              {
                party: actAs,
                signatures: [
                  {
                    format: "SIGNATURE_FORMAT_RAW",
                    signingAlgorithmSpec: "SIGNING_ALGORITHM_SPEC_ED25519",
                    signature: signature.toString("base64"),
                    signedBy: actAs.split("::")[1],
                  },
                ],
              },
            ],
          },
        }),
      ),
    );
  }

  async queryContracts(
    parties: string[],
    templateIds: string[],
  ): Promise<CreatedEvent[]> {
    const ledgerEnd = (
      await this.als.run({ url: undefined }, () =>
        this.api.getV2StateLedgerEnd(),
      )
    ).offset;
    const toTemplateFilter = (t: string) => {
      const idFilter = new IdentifierFilter();
      idFilter.TemplateFilter = {
        value: { includeCreatedEventBlob: false, templateId: t },
      };
      return idFilter;
    };
    const filters: Filters = {
      cumulative: templateIds.map((t) => ({
        identifierFilter: toTemplateFilter(t),
      })),
    };
    const request: GetActiveContractsRequest = {
      verbose: false,
      activeAtOffset: ledgerEnd,
      eventFormat: {
        verbose: false,
        filtersByParty: Object.fromEntries(parties.map((p) => [p, filters])),
      },
    };
    const responses = await this.als.run({ url: undefined }, () =>
      this.api.postV2StateActiveContracts(request),
    );
    return responses.flatMap((r) =>
      r.contractEntry.JsActiveContract.createdEvent
        ? [r.contractEntry.JsActiveContract.createdEvent]
        : [],
    );
  }

  async grantExecuteAndReadAsAnyPartyRights(
    userId: string,
  ): Promise<GrantUserRightsResponse> {
    const executeRight = new Kind();
    executeRight.CanExecuteAsAnyParty = { value: {} };
    // execute does not imply read as so we also need to grant that.
    const readRight = new Kind();
    readRight.CanReadAsAnyParty = { value: {} };
    return this.retry(
      `Grant ExecuteAsAnyParty and ReadAsAnyParty rights to ${userId}`,
      () =>
        this.als.run({ url: undefined }, () =>
          this.api.postV2UsersUserIdRights(userId, {
            userId: userId,
            identityProviderId: "",
            rights: [{ kind: executeRight }, { kind: readRight }],
          }),
        ),
    );
  }

  async retry<T>(
    description: string,
    task: () => Promise<T>,
    maxRetries: number = 60,
    delayMs: number = 1000,
  ): Promise<T> {
    let attempt = 1;
    // eslint-disable-next-line no-constant-condition
    while (true) {
      try {
        return await task();
      } catch (e: unknown) {
        const errorMessage = e instanceof Error ? e.message : JSON.stringify(e);
        if (attempt < maxRetries) {
          logger.info(
            `Task ${description} failed after ${attempt} attempts (max ${maxRetries}): ${errorMessage}`,
          );
          await delay(delayMs);
        } else {
          logger.error(
            `Task ${description} failed after ${attempt} attempts, giving up: ${errorMessage}`,
          );
          throw e;
        }
      }
      attempt++;
    }
  }
}
