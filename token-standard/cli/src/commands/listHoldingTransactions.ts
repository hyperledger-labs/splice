// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  createLedgerApiClient,
  ensureHoldingViewIsPresent,
  filtersByParty,
} from "../apis/ledger-api-utils";
import { CommandOptions } from "../cli";
import {
  HoldingInterface,
  TokenStandardTransactionInterfaces,
} from "../constants";
import {
  DefaultApi as LedgerJsonApi,
  JsGetUpdatesResponse,
  Event as LedgerApiEvent,
  ExercisedEvent as LedgerApiExercisedEvent,
  CreatedEvent as LedgerApiCreatedEvent,
  ArchivedEvent as LedgerApiArchivedEvent,
} from "canton-json-api-v2-openapi";

// TODO (#18773): change approach
export async function listHoldingTransactions(
  partyId: string,
  opts: CommandOptions & { afterOffset?: string }
): Promise<void> {
  try {
    const ledgerClient: LedgerJsonApi = createLedgerApiClient(opts);
    const afterOffset =
      Number(opts.afterOffset) ||
      (await ledgerClient.getV2StateLatestPrunedOffsets())
        .participantPrunedUpToInclusive;
    const updates = await ledgerClient.postV2UpdatesFlats({
      updateFormat: {
        includeTransactions: {
          eventFormat: {
            filtersByParty: filtersByParty(
              partyId,
              TokenStandardTransactionInterfaces,
              true
            ),
            verbose: false,
          },
          transactionShape: {
            TRANSACTION_SHAPE_LEDGER_EFFECTS: {},
            unrecognizedValue: 0,
          },
        },
      },
      beginExclusive: afterOffset,
      verbose: false,
    });
    const acs = (
      await ledgerClient.postV2StateActiveContracts({
        filter: {
          filtersByParty: filtersByParty(
            partyId,
            TokenStandardTransactionInterfaces,
            false
          ),
        },
        verbose: false,
        activeAtOffset: afterOffset,
      })
    ).map((c) => c.contractEntry.JsActiveContract.createdEvent.contractId);
    console.log(
      JSON.stringify(
        await toPrettyTransactions(
          updates,
          partyId,
          new Set(acs),
          ledgerClient
        ),
        null,
        2
      )
    );
  } catch (err) {
    console.error("Failed to list holding transactions.", err);
  }
}

async function toPrettyTransactions(
  updates: JsGetUpdatesResponse[],
  partyId: string,
  mutatingHoldingsAcs: Set<string>,
  ledgerClient: LedgerJsonApi
): Promise<PrettyTransactions> {
  const offsetCheckpoints: number[] = updates
    .filter((update) => update.update.OffsetCheckpoint)
    .map((update) => update.update.OffsetCheckpoint.value.offset);
  const latestCheckpointOffset = Math.max(...offsetCheckpoints);

  const transactions: PrettyTransaction[] = await Promise.all(
    updates
      // exclude OffsetCheckpoint, Reassignment, TopologyTransaction
      .filter((update) => !!update.update?.Transaction?.value)
      .map(async (update) => {
        const tx = update.update.Transaction.value;
        const rawEvents = tx.events || [];

        const events = await toPrettyEvents(
          rawEvents.reverse(),
          [],
          partyId,
          mutatingHoldingsAcs,
          [],
          -1,
          ledgerClient
        );
        return {
          updateId: tx.updateId,
          offset: tx.offset,
          recordTime: tx.recordTime,
          synchronizerId: tx.synchronizerId,
          events,
        };
      })
  );

  return {
    // OffsetCheckpoint can be anywhere... or not at all, maybe
    nextOffset: Math.max(
      latestCheckpointOffset,
      ...transactions.map((tx) => tx.offset)
    ),
    transactions: transactions.filter((tx) => tx.events.length > 0),
  };
}

async function toPrettyEvents(
  pendingEventsMutatingStack: LedgerApiEvent[],
  mutatingResult: Event[],
  partyId: string,
  mutatingHoldingAcs: Set<string>,
  parentChoiceNames: string[],
  continueAfterNodeId: number,
  ledgerClient: LedgerJsonApi
): Promise<Event[]> {
  const currentEvent = pendingEventsMutatingStack.pop();
  if (!currentEvent) return mutatingResult;

  const { nodeId, createdEvent, archivedEvent, exercisedEvent } =
    getNodeIdAndEvent(currentEvent);
  const parentChoice =
    parentChoiceNames[parentChoiceNames.length - 1] || "raw API";

  if (nodeId <= continueAfterNodeId) {
    return toPrettyEvents(
      pendingEventsMutatingStack,
      mutatingResult,
      partyId,
      mutatingHoldingAcs,
      parentChoiceNames,
      continueAfterNodeId,
      ledgerClient
    );
  } else if (exercisedEvent) {
    switch (exercisedEvent.choice) {
      case "TransferFactory_Transfer":
        const transfer = await tokenStandardChoiceExercised(
          toPrettyTransfer(exercisedEvent),
          exercisedEvent,
          pendingEventsMutatingStack,
          partyId,
          mutatingHoldingAcs,
          ledgerClient
        );
        mutatingResult.push(transfer);
        return toPrettyEvents(
          pendingEventsMutatingStack,
          mutatingResult,
          partyId,
          mutatingHoldingAcs,
          parentChoiceNames,
          exercisedEvent.lastDescendantNodeId, // Children already parsed
          ledgerClient
        );
      case "BurnMintFactory_BurnMint":
        const pretty = toPrettyBurnMint(exercisedEvent, partyId);
        if (pretty) {
          const burnMint = await tokenStandardChoiceExercised(
            pretty,
            exercisedEvent,
            pendingEventsMutatingStack,
            partyId,
            mutatingHoldingAcs,
            ledgerClient
          );
          mutatingResult.push(burnMint);
        }
        return toPrettyEvents(
          pendingEventsMutatingStack,
          mutatingResult,
          partyId,
          mutatingHoldingAcs,
          parentChoiceNames,
          exercisedEvent.lastDescendantNodeId, // Children already parsed
          ledgerClient
        );
      default:
        parentChoiceNames.push(exercisedEvent.choice);
        const result = toPrettyEvents(
          pendingEventsMutatingStack,
          mutatingResult,
          partyId,
          mutatingHoldingAcs,
          parentChoiceNames,
          // we want to parse the children too
          exercisedEvent.nodeId,
          ledgerClient
        );
        parentChoiceNames.pop();
        return result;
    }
  } else if (createdEvent) {
    if (shouldIgnoreCreateEvent(createdEvent, partyId)) {
      // Not a holding, or the party doesn't care about what happens there
      return toPrettyEvents(
        pendingEventsMutatingStack,
        mutatingResult,
        partyId,
        mutatingHoldingAcs,
        parentChoiceNames,
        createdEvent.nodeId,
        ledgerClient
      );
    } else {
      const holdingView = ensureHoldingViewIsPresent(createdEvent);
      const payload = {
        ...holdingView,
        viewStatus:
          holdingView?.viewStatus?.code === 0
            ? undefined
            : holdingView?.viewStatus,
      };

      mutatingHoldingAcs.add(createdEvent.contractId);
      const rawCreatedEvent: RawCreatedEvent = {
        type: "Created",
        parentChoice,
        contractId: createdEvent.contractId,
        payload,
        offset: createdEvent.offset,
        templateId: createdEvent.templateId,
        packageName: createdEvent.packageName,
      };
      mutatingResult.push(rawCreatedEvent);
      return toPrettyEvents(
        pendingEventsMutatingStack,
        mutatingResult,
        partyId,
        mutatingHoldingAcs,
        parentChoiceNames,
        createdEvent.nodeId,
        ledgerClient
      );
    }
  } else if (archivedEvent) {
    const prettyArchive = toPrettyArchiveEvent(
      archivedEvent,
      mutatingHoldingAcs,
      parentChoice
    );
    prettyArchive && mutatingResult.push(prettyArchive);
    return toPrettyEvents(
      pendingEventsMutatingStack,
      mutatingResult,
      partyId,
      mutatingHoldingAcs,
      parentChoiceNames,
      archivedEvent.nodeId,
      ledgerClient
    );
  } else {
    throw new Error(`Unknown event: ${JSON.stringify(currentEvent)}`);
  }
}

function toPrettyArchiveEvent(
  event: LedgerApiArchivedEvent | LedgerApiExercisedEvent,
  mutatingHoldingAcs: Set<string>,
  parentChoice: string
): RawArchivedEvent | null {
  // contract is unknown to the sender or not a holding
  if (shouldIgnoreArchiveEvent(event, mutatingHoldingAcs)) {
    return null;
  } else {
    mutatingHoldingAcs.delete(event.contractId);
    return {
      type: "Archived",
      parentChoice,
      contractId: event.contractId,
      offset: event.offset,
      templateId: event.templateId,
      packageName: event.packageName,
      actingParties: (event as LedgerApiExercisedEvent).actingParties || [],
    };
  }
}

function shouldIgnoreCreateEvent(
  createdEvent: LedgerApiCreatedEvent,
  partyId: string
): boolean {
  const holdingView =
    createdEvent.interfaceViews && createdEvent.interfaceViews[0];
  return !holdingView || holdingView.viewValue.owner !== partyId;
}

function shouldIgnoreArchiveEvent(
  event: LedgerApiArchivedEvent,
  acs: Set<string>
): boolean {
  const interfaceId: string | undefined =
    event.implementedInterfaces && event.implementedInterfaces[0];
  return !interfaceId || !acs.has(event.contractId);
}

function toPrettyTransfer(
  exercisedEvent: LedgerApiExercisedEvent
): PrettyTransfer {
  const choiceArgument = exercisedEvent.choiceArgument;
  const exerciseResult = exercisedEvent.exerciseResult;
  return {
    type: "Transfer",
    status:
      exerciseResult.output.tag === "TransferInstructionResult_Completed"
        ? "Completed"
        : "Pending",
    input: {
      sender: choiceArgument.transfer.sender,
      receiver: choiceArgument.transfer.receiver,
      amount: choiceArgument.transfer.amount,
      instrumentId: choiceArgument.transfer.instrumentId,
      senderHoldings: choiceArgument.transfer.inputHoldingCids,
      meta: choiceArgument.transfer.meta,
      extraArgs: choiceArgument.extraArgs,
    },
    output: {
      receiverHoldingCids:
        exerciseResult.output.value.holdings.senderHoldingCids,
      senderHoldingCids:
        exerciseResult.output.value.holdings.receiverHoldingCids,
      meta: exerciseResult.meta,
    },
  };
}

function toPrettyBurnMint(
  exercisedEvent: LedgerApiExercisedEvent,
  partyId: string
): PrettyBurnMint | null {
  const choiceArgument = exercisedEvent.choiceArgument;
  const exerciseResult = exercisedEvent.exerciseResult;
  if (
    !choiceArgument.outputs.find((output: any) => output.owner === partyId) &&
    choiceArgument.sender !== partyId
  ) {
    return null;
  }

  const inputHoldings = choiceArgument.inputHoldingCids;
  const outputHoldings = choiceArgument.outputs.map(
    (output: any, idx: number) => {
      return {
        [exerciseResult.outputCids[idx]]: {
          amount: output.amount,
          context: output.context,
          owner: output.owner,
        },
      };
    }
  );
  return {
    type: "BurnMint",
    input: {
      sender: choiceArgument.sender,
      inputHoldings,
      instrumentId: choiceArgument.instrumentId,
      extraArgs: choiceArgument.extraArgs,
    },
    output: {
      outputHoldings,
    },
  };
}

// a naive implementation like event.X?.nodeId || event.Y?.nodeId || event.Z?.nodeId fails when nodeId=0
interface NodeIdAndEvent {
  nodeId: number;
  exercisedEvent?: LedgerApiExercisedEvent;
  archivedEvent?: LedgerApiArchivedEvent | LedgerApiExercisedEvent;
  createdEvent?: LedgerApiCreatedEvent;
}
function getNodeIdAndEvent(event: LedgerApiEvent): NodeIdAndEvent {
  if (event.ExercisedEvent) {
    // ledger API's TRANSACTION_SHAPE_LEDGER_EFFECTS does not include ArchivedEvent, instead has the choice as Archive
    if (event.ExercisedEvent.choice === "Archive") {
      return {
        nodeId: event.ExercisedEvent.nodeId,
        archivedEvent: event.ExercisedEvent,
      };
    } else {
      return {
        nodeId: event.ExercisedEvent.nodeId,
        exercisedEvent: event.ExercisedEvent,
      };
    }
  } else if (event.CreatedEvent)
    return {
      nodeId: event.CreatedEvent.nodeId,
      createdEvent: event.CreatedEvent,
    };
  else if (event.ArchivedEvent)
    return {
      nodeId: event.ArchivedEvent.nodeId,
      archivedEvent: event.ArchivedEvent,
    };
  else throw new Error(`Unknown event type: ${event}`);
}

interface PrettyTransactions {
  transactions: PrettyTransaction[];
  nextOffset: number;
}
interface PrettyTransaction {
  updateId: string;
  offset: number;
  recordTime: string;
  synchronizerId: string;
  events: Event[];
}
type Event = RawCreatedEvent | RawArchivedEvent | ExercisedEvent;
type ExercisedEvent = TokenStandardChoiceExercised & CreateArchiveChildren;
type TokenStandardChoiceExercised = PrettyTransfer | PrettyBurnMint;
interface RawArchivedEvent {
  type: "Archived";
  parentChoice: string;
  contractId: string;
  offset: number;
  templateId: string;
  packageName: string;
  actingParties: string[];
}
interface RawCreatedEvent {
  type: "Created";
  parentChoice: string;
  contractId: string;
  offset: number;
  templateId: string;
  payload: any;
  packageName: string;
}
interface PrettyTransfer {
  type: "Transfer";
  status: "Completed" | "Pending";
  input: {
    sender: string;
    receiver: string;
    amount: string;
    instrumentId: { admin: string; id: string };
    senderHoldings: string[];
    meta: any;
    extraArgs: any;
  };
  output: {
    senderHoldingCids: string[];
    receiverHoldingCids: string[];
    meta: any;
  };
}
interface PrettyBurnMint {
  type: "BurnMint";
  input: {
    sender: string;
    inputHoldings: {
      [contractId: string]: {
        amount: string;
      };
    };
    instrumentId: { admin: string; id: string };
    extraArgs: any;
  };
  output: {
    outputHoldings: {
      [contractId: string]: {
        amount: string;
        owner: string;
        context: any;
      };
    };
  };
}

type Holding = {
  contractId: string;
  amount: string;
  owner: string;
  instrumentId: { admin: string; id: string };
};

async function tokenStandardChoiceExercised(
  pretty: TokenStandardChoiceExercised,
  exercisedEvent: LedgerApiExercisedEvent,
  pendingEventsMutatingStack: LedgerApiEvent[],
  partyId: string,
  mutatingHoldingAcs: Set<string>,
  ledgerClient: LedgerJsonApi
): Promise<ExercisedEvent> {
  const createsAndArchives = await findCreatesAndArchives(
    pendingEventsMutatingStack
      .reverse()
      .filter(
        (evt) =>
          getNodeIdAndEvent(evt).nodeId <= exercisedEvent.lastDescendantNodeId
      ),
    partyId,
    mutatingHoldingAcs,
    ledgerClient
  );
  return { ...pretty, ...createsAndArchives };
}

interface CreateArchiveChildren {
  creates: Holding[];
  archives: Holding[];
}
async function findCreatesAndArchives(
  mutatingEventsSlice: LedgerApiEvent[],
  partyId: string,
  mutatingAcs: Set<string>,
  ledgerClient: LedgerJsonApi,
  mutatingResult: CreateArchiveChildren = { creates: [], archives: [] }
): Promise<CreateArchiveChildren> {
  const child = mutatingEventsSlice.pop();
  if (!child) {
    return mutatingResult;
  }

  const { createdEvent, archivedEvent } = getNodeIdAndEvent(child);

  if (createdEvent && !shouldIgnoreCreateEvent(createdEvent, partyId)) {
    mutatingAcs.add(createdEvent.contractId);
    const holdingView = ensureHoldingViewIsPresent(createdEvent).viewValue;
    mutatingResult.creates.push({
      amount: holdingView.amount,
      instrumentId: holdingView.instrumentId,
      contractId: createdEvent.contractId,
      owner: holdingView.owner,
    });
  } else if (
    archivedEvent &&
    !shouldIgnoreArchiveEvent(archivedEvent, mutatingAcs)
  ) {
    mutatingAcs.delete(archivedEvent.contractId);
    const contractEvents = await ledgerClient.postV2EventsEventsByContractId({
      contractId: archivedEvent.contractId,
      eventFormat: {
        filtersByParty: filtersByParty(partyId, [HoldingInterface], true),
        verbose: false,
      },
      requestingParties: [], // this field will be removed according to openapi docs, but it's mandatory
    });
    if (!contractEvents.created) {
      throw new Error(
        `Contract ${
          archivedEvent.contractId
        } was archived but has no CreatedEvent. Response: ${JSON.stringify(
          contractEvents
        )}`
      );
    }
    const holdingView = ensureHoldingViewIsPresent(
      contractEvents.created?.createdEvent
    ).viewValue;
    mutatingResult.archives.push({
      amount: holdingView.amount,
      instrumentId: holdingView.instrumentId,
      contractId: archivedEvent.contractId,
      owner: holdingView.owner,
    });
  }

  return findCreatesAndArchives(
    mutatingEventsSlice,
    partyId,
    mutatingAcs,
    ledgerClient,
    mutatingResult
  );
}
