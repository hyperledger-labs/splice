// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AllKnownMetaKeys,
  HoldingInterface,
  InterfaceId,
  TransferInstructionInterface,
} from "../constants";
import { CommandOptions } from "../token-standard-cli";
import {
  ArchivedEvent as LedgerApiArchivedEvent,
  Command,
  createConfiguration,
  CreatedEvent as LedgerApiCreatedEvent,
  DeduplicationPeriod2,
  DefaultApi as LedgerJsonApi,
  ExerciseCommand,
  ExercisedEvent as LedgerApiExercisedEvent,
  HttpAuthAuthentication,
  JsInterfaceView,
  PartySignatures,
  ServerConfiguration,
  TransactionFilter,
} from "@lfdecentralizedtrust/canton-json-api-v2-openapi";
import { DisclosedContract } from "@lfdecentralizedtrust/transfer-instruction-openapi";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import crypto from "crypto";

export function createLedgerApiClient(opts: CommandOptions): LedgerJsonApi {
  return new LedgerJsonApi(
    createConfiguration({
      baseServer: new ServerConfiguration(opts.ledgerUrl, {}),
      authMethods: {
        default: new HttpAuthAuthentication({
          getToken(): Promise<string> | string {
            return Promise.resolve(opts.authToken);
          },
        }),
      },
    }),
  );
}

export function filtersByParty(
  party: string,
  interfaceNames: InterfaceId[],
  includeWildcard: boolean,
): TransactionFilter["filtersByParty"] {
  return {
    [party]: {
      cumulative: interfaceNames
        .map((interfaceName) => {
          return {
            identifierFilter: {
              InterfaceFilter: {
                value: {
                  interfaceId: interfaceName.toString(),
                  includeInterfaceView: true,
                  includeCreatedEventBlob: true,
                },
              },
            },
          } as any;
        })
        .concat(
          includeWildcard
            ? [
                {
                  identifierFilter: {
                    WildcardFilter: {
                      value: {
                        includeCreatedEventBlob: true,
                      },
                    },
                  },
                },
              ]
            : [],
        ),
    },
  };
}

export function hasInterface(
  interfaceId: InterfaceId,
  event: LedgerApiExercisedEvent | LedgerApiArchivedEvent,
): boolean {
  return (event.implementedInterfaces || []).some((id) =>
    interfaceId.matches(id),
  );
}

export function getInterfaceView(
  createdEvent: LedgerApiCreatedEvent,
): JsInterfaceView | null {
  const interfaceViews = createdEvent.interfaceViews || null;
  return (interfaceViews && interfaceViews[0]) || null;
}

export type KnownInterfaceView = {
  type: "Holding" | "TransferInstruction";
  viewValue: any;
};
export function getKnownInterfaceView(
  createdEvent: LedgerApiCreatedEvent,
): KnownInterfaceView | null {
  const interfaceView = getInterfaceView(createdEvent);
  if (!interfaceView) {
    return null;
  } else if (HoldingInterface.matches(interfaceView.interfaceId)) {
    return { type: "Holding", viewValue: interfaceView.viewValue };
  } else if (TransferInstructionInterface.matches(interfaceView.interfaceId)) {
    return { type: "TransferInstruction", viewValue: interfaceView.viewValue };
  } else {
    return null;
  }
}

// TODO (#563): handle allocations in such a way that any callers have to handle them too
/**
 * Use this when `createdEvent` is guaranteed to have an interface view because the ledger api filters
 * include it, and thus is guaranteed to be returned by the API.
 */
export function ensureInterfaceViewIsPresent(
  createdEvent: LedgerApiCreatedEvent,
  interfaceId: InterfaceId,
): JsInterfaceView {
  const interfaceView = getInterfaceView(createdEvent);
  if (!interfaceView) {
    throw new Error(
      `Expected to have interface views, but didn't: ${JSON.stringify(
        createdEvent,
      )}`,
    );
  }
  if (!interfaceId.matches(interfaceView.interfaceId)) {
    throw new Error(
      `Not a ${interfaceId.toString()} but a ${
        interfaceView.interfaceId
      }: ${JSON.stringify(createdEvent)}`,
    );
  }
  return interfaceView;
}

type Meta = { values: { [key: string]: string } } | undefined;

export function mergeMetas(event: LedgerApiExercisedEvent): Meta {
  const lastWriteWins = [
    event.choiceArgument?.transfer?.meta,
    event.choiceArgument?.extraArgs?.meta,
    event.choiceArgument?.meta,
    event.exerciseResult?.meta,
  ];
  const result: { [key: string]: string } = {};
  lastWriteWins.forEach((meta) => {
    const values: { [key: string]: string } = meta?.values || [];
    Object.entries(values).forEach(([k, v]) => {
      result[k] = v;
    });
  });
  if (Object.keys(result).length === 0) {
    return undefined;
  }
  // order of keys doesn't matter, but we return it consistent for test purposes (and it's nicer)
  else {
    return { values: result };
  }
}

export function getMetaKeyValue(key: string, meta: Meta): string | null {
  return (meta?.values || {})[key] || null;
}

/**
 * From the view of making it easy to build the display for the wallet,
 * we remove all metadata fields that were fully parsed, and whose content is reflected in the TypeScript structure.
 * Otherwise, the display code has to do so, overloading the user with superfluous metadata entries.
 */
export function removeParsedMetaKeys(meta: Meta): Meta {
  return {
    values: Object.fromEntries(
      Object.entries(meta?.values || {}).filter(
        ([k]) => !AllKnownMetaKeys.includes(k),
      ),
    ),
  };
}

export async function submitExerciseCommand(
  ledgerClient: LedgerJsonApi,
  exerciseCommand: ExerciseCommand,
  disclosedContracts: DisclosedContract[],
  partyId: string,
  userId: string,
  publicKeyPath: string,
  privateKeyPath: string,
): Promise<Completion> {
  const submissionId = randomUUID();
  const commandId = `tscli-${randomUUID()}`;

  const command = new Command();
  command.ExerciseCommand = exerciseCommand;

  const synchronizerId =
    getSynchronizerIdFromDisclosedContracts(disclosedContracts);

  const prepared = await ledgerClient.postV2InteractiveSubmissionPrepare({
    actAs: [partyId],
    readAs: [partyId],
    userId: userId,
    commandId,
    synchronizerId,
    commands: [command],
    disclosedContracts,
    verboseHashing: true,
    packageIdSelectionPreference: [],
  });

  const signed = signTransaction(
    publicKeyPath,
    privateKeyPath,
    prepared.preparedTransactionHash!,
  );
  const partySignatures: PartySignatures = {
    signatures: [
      {
        party: partyId,
        signatures: [
          {
            signature: signed.signedHash,
            signedBy: signed.signedBy,
            format: "SIGNATURE_FORMAT_RAW",
            signingAlgorithmSpec: "SIGNING_ALGORITHM_SPEC_ED25519",
          },
        ],
      },
    ],
  };

  const deduplicationPeriod = new DeduplicationPeriod2();
  deduplicationPeriod.Empty = {};

  const ledgerEnd = await ledgerClient.getV2StateLedgerEnd();

  await ledgerClient.postV2InteractiveSubmissionExecute({
    userId,
    submissionId,
    preparedTransaction: prepared.preparedTransaction!,
    hashingSchemeVersion: prepared.hashingSchemeVersion!,
    partySignatures,
    deduplicationPeriod,
  });

  const completionPromise = awaitCompletion(
    ledgerClient,
    ledgerEnd.offset!,
    partyId,
    userId,
    commandId,
    submissionId,
  );
  return promiseWithTimeout(
    completionPromise,
    45_000 * 2, // 45s
    `Timed out getting completion for submission with userId=${userId}, commandId=${commandId}, submissionId=${submissionId}.
    The submission might have succeeded or failed, but it couldn't be determined in time.`,
  );
}

// The synchronizer id is mandatory, so we derive it from the disclosed contracts,
// expecting that they'll all be in the same synchronizer
function getSynchronizerIdFromDisclosedContracts(
  disclosedContracts: DisclosedContract[],
): string {
  const synchronizerId = disclosedContracts[0].synchronizerId;
  const differentSynchronizerId = disclosedContracts.find(
    (dc) => dc.synchronizerId !== synchronizerId,
  );
  if (differentSynchronizerId) {
    throw new Error(
      `Contract is in a different domain so can't submit to the correct synchronizer: ${JSON.stringify(
        differentSynchronizerId,
      )}`,
    );
  }
  return synchronizerId;
}

interface SignTransactionResult {
  signedBy: string;
  // base64 encoded
  signedHash: string;
}
function signTransaction(
  publicKeyPath: string,
  privateKeyPath: string,
  preparedTransactionHash: string,
): SignTransactionResult {
  const publicKey = readFileSync(publicKeyPath);
  const nodePublicKey = crypto.createPublicKey({
    key: publicKey,
    format: "der",
    type: "spki", // pycryptodome exports public keys as SPKI
  });

  const privateKey = readFileSync(privateKeyPath);
  const nodePrivateKey = crypto.createPrivateKey({
    key: privateKey,
    format: "der",
    type: "pkcs8",
  });

  const keyFingerprint = crypto
    .createHash("sha256")
    .update(
      Buffer.from(
        `0000000C${nodePublicKey
          .export({ format: "der", type: "spki" })
          // Ed25519 public key is the last 32 bytes of the SPKI DER key
          .subarray(-32)
          .toString("hex")}`,
        "hex",
      ),
    )
    .digest("hex");
  const fingerprintPreFix = "1220"; // 12 PublicKeyFingerprint, 20 is a special length encoding
  const signedBy = `${fingerprintPreFix}${keyFingerprint}`;

  const hashBuffer = Buffer.from(preparedTransactionHash, "base64");
  const signedHash = crypto
    .sign(null, hashBuffer, {
      key: nodePrivateKey,
      dsaEncoding: "ieee-p1363",
    })
    .toString("base64");

  return {
    signedBy,
    signedHash,
  };
}

interface Completion {
  updateId: string;
  // the openAPI definition claims these two can be null
  synchronizerId?: string;
  recordTime?: string;
}

const COMPLETIONS_LIMIT = 100;
const COMPLETIONS_STREAM_IDLE_TIMEOUT_MS = 1000;
/**
 * Polls the completions endpoint until
 * the completion with the given (userId, commandId, submissionId) is returned.
 * Then returns the updateId, synchronizerId and recordTime of that completion.
 */
async function awaitCompletion(
  ledgerClient: LedgerJsonApi,
  ledgerEnd: number,
  partyId: string,
  userId: string,
  commandId: string,
  submissionId: string,
): Promise<Completion> {
  const responses = await ledgerClient.postV2CommandsCompletions(
    {
      userId,
      parties: [partyId],
      beginExclusive: ledgerEnd,
    },
    COMPLETIONS_LIMIT,
    COMPLETIONS_STREAM_IDLE_TIMEOUT_MS,
  );
  const completions = responses.filter(
    (response) => !!response.completionResponse!.Completion,
  );

  const wantedCompletion = completions.find((response) => {
    const completion = response.completionResponse!.Completion;
    return (
      completion.value!.userId === userId &&
      completion.value!.commandId === commandId &&
      completion.value!.submissionId === submissionId
    );
  });

  if (wantedCompletion) {
    const status =
      wantedCompletion.completionResponse?.Completion.value?.status;
    if (status && status.code !== 0) {
      // status.code is 0 for success
      throw new Error(
        `Command failed with status: ${JSON.stringify(wantedCompletion.completionResponse?.Completion.value?.status)}`,
      );
    }
    return {
      synchronizerId:
        wantedCompletion.completionResponse?.Completion.value?.synchronizerTime
          ?.synchronizerId,
      recordTime:
        wantedCompletion.completionResponse?.Completion.value?.synchronizerTime
          ?.recordTime,
      updateId:
        wantedCompletion.completionResponse?.Completion.value?.updateId ?? "",
    };
  } else {
    const lastCompletion = completions[completions.length - 1];
    const newLedgerEnd =
      lastCompletion?.completionResponse?.Completion.value?.offset;
    return awaitCompletion(
      ledgerClient,
      newLedgerEnd || ledgerEnd, // !newLedgerEnd implies response was empty
      partyId,
      userId,
      commandId,
      submissionId,
    );
  }
}

async function promiseWithTimeout<T>(
  promise: Promise<T>,
  timeoutMs: number,
  errorMessage: string,
): Promise<T> {
  let timeoutPid: NodeJS.Timeout | null = null;
  const timeoutPromise: Promise<T> = new Promise((_resolve, reject) => {
    timeoutPid = setTimeout(() => reject(errorMessage), timeoutMs);
  });

  try {
    return await Promise.race([promise, timeoutPromise]);
  } finally {
    if (timeoutPid) {
      clearTimeout(timeoutPid);
    }
  }
}
