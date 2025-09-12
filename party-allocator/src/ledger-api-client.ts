// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AllocateExternalPartyResponse,
  Command,
  createConfiguration,
  DeduplicationPeriod2,
  DefaultApi,
  DisclosedContract,
  GenerateExternalPartyTopologyResponse,
  HttpAuthAuthentication,
  RequestContext,
  ResponseContext,
  ServerConfiguration,
  Signature,
  SignedTransaction,
  SigningPublicKey,
} from "@lfdecentralizedtrust/canton-json-api-v2-openapi";
import { AsyncLocalStorage } from "node:async_hooks";
import * as crypto from "node:crypto";

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
              console.log(`[Response] ${url} ${context.httpStatusCode}`);
              return context;
            },
            pre: async (context: RequestContext) => {
              console.log(`[Request] ${context.getUrl()}`);
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
    synchronizer: string,
    onboardingTransactions: SignedTransaction[],
    multiHashSignatures: Signature[],
  ): Promise<AllocateExternalPartyResponse> {
    return this.retry(`allocate external party`, () =>
      this.als.run({ url: undefined }, () =>
        this.api.postV2PartiesExternalAllocate({
          synchronizer,
          identityProviderId: "",
          onboardingTransactions,
          multiHashSignatures,
        }),
      ),
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
          userId: "participant_admin",
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
          userId: "participant_admin",
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

  async retry<T>(
    description: string,
    task: () => Promise<T>,
    maxRetries: number = 20,
    delayMs: number = 1000,
  ): Promise<T> {
    let attempt = 1;
    // eslint-disable-next-line no-constant-condition
    while (true) {
      try {
        return await task();
      } catch (e: unknown) {
        if (attempt < maxRetries) {
          const errorMessage =
            e instanceof Error ? e.message : JSON.stringify(e);
          console.error(
            `Task ${description} failed after ${attempt} attempts: ${errorMessage}`,
          );
          await delay(delayMs);
        } else {
          throw e;
        }
      }
      attempt++;
    }
  }
}
