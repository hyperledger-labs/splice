// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { LedgerClient } from "../apis/ledger-client";
import { CommandOptions } from "../cli";
import { TokenStandardTransactionInterfaces } from "../constants";

// TODO (#18634): support verbose flag
export async function listHoldingTransactions(
  partyId: string,
  opts: CommandOptions & { afterOffset?: string }
): Promise<void> {
  try {
    const ledgerClient = new LedgerClient(opts.ledgerUrl, opts.authToken);
    const afterOffset =
      opts.afterOffset ||
      (await ledgerClient.getLatestPrunedOffsets())
        .participantPrunedUpToInclusive;
    const updates = await ledgerClient.getUpdates(
      partyId,
      TokenStandardTransactionInterfaces,
      afterOffset
    );
    const acs = (
      await ledgerClient.getActiveContractsOfParty(
        partyId,
        afterOffset,
        TokenStandardTransactionInterfaces
      )
    ).map((c) => c.contractEntry.JsActiveContract.createdEvent.contractId);
    console.log(
      JSON.stringify(
        toPrettyTransactions(updates, partyId, new Set(acs)),
        null,
        2
      )
    );
  } catch (err) {
    console.error("Failed to list holding transactions.", err);
  }
}

function toPrettyTransactions(
  updates: any[],
  partyId: string,
  mutatingHoldingsAcs: Set<string>
): PrettyTransactions {
  const offsetCheckpoints: number[] = updates
    .filter((update) => update.update.OffsetCheckpoint)
    .map((update) => update.update.OffsetCheckpoint.value.offset);
  const latestCheckpointOffset = Math.max(...offsetCheckpoints);

  const transactions: PrettyTransaction[] = updates
    // exclude OffsetCheckpoint, Reassignment, TopologyTransaction
    .filter((update) => !!update.update?.Transaction?.value)
    .map((update) => {
      const tx = update.update.Transaction.value;
      const rawEvents: any[] = tx.events;

      const events: Event[] = toPrettyEvents(
        rawEvents.reverse(),
        [],
        partyId,
        mutatingHoldingsAcs,
        [],
        -1
      );

      return {
        updateId: tx.updateId,
        offset: tx.offset,
        recordTime: tx.recordTime,
        synchronizerId: tx.synchronizerId,
        events,
      };
    });

  return {
    // OffsetCheckpoint can be anywhere... or not at all, maybe
    nextOffset: Math.max(
      latestCheckpointOffset,
      ...transactions.map((tx) => tx.offset)
    ),
    transactions: transactions.filter((tx) => tx.events.length > 0),
  };
}

function toPrettyEvents(
  pendingEventsMutatingStack: any[],
  mutatingResult: Event[],
  partyId: string,
  mutatingHoldingAcs: Set<string>,
  parentChoiceNames: string[],
  continueAfterNodeId: number
): Event[] {
  if (pendingEventsMutatingStack.length === 0) return mutatingResult;
  else {
    const currentEvent = pendingEventsMutatingStack.pop();
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
        continueAfterNodeId
      );
    } else if (exercisedEvent) {
      // ledger API's TRANSACTION_SHAPE_LEDGER_EFFECTS does not include ArchivedEvent, instead has the choice as Archive
      if (exercisedEvent.choice === "Archive") {
        const prettyArchive = toPrettyArchiveEvent(
          exercisedEvent,
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
          exercisedEvent.nodeId
        );
      } else {
        switch (exercisedEvent.choice) {
          case "TransferFactory_Transfer":
            const transfer = toPrettyTransfer(exercisedEvent);
            mutatingResult.push(transfer);
            return toPrettyEvents(
              pendingEventsMutatingStack,
              mutatingResult,
              partyId,
              mutatingHoldingAcs,
              parentChoiceNames,
              exercisedEvent.lastDescendantNodeId // We don't care about a transfer's children
            );
          case "BurnMintFactory_BurnMint":
            const burnMint = toPrettyBurnMint(exercisedEvent, partyId);
            if (burnMint) {
              mutatingResult.push(burnMint);
            }
            return toPrettyEvents(
              pendingEventsMutatingStack,
              mutatingResult,
              partyId,
              mutatingHoldingAcs,
              parentChoiceNames,
              exercisedEvent.lastDescendantNodeId // We don't care about burnmint's children
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
              exercisedEvent.nodeId
            );
            parentChoiceNames.pop();
            return result;
        }
      }
    } else if (createdEvent) {
      const holdingView = createdEvent.interfaceViews[0];
      if (!holdingView || holdingView.viewValue.owner !== partyId) {
        // Not a holding, or the party doesn't care about what happens there
        return toPrettyEvents(
          pendingEventsMutatingStack,
          mutatingResult,
          partyId,
          mutatingHoldingAcs,
          parentChoiceNames,
          createdEvent.nodeId
        );
      } else {
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
          createdEvent.nodeId
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
        archivedEvent.nodeId
      );
    } else {
      throw new Error(`Unknown event: ${JSON.stringify(currentEvent)}`);
    }
  }
}

function toPrettyArchiveEvent(
  event: any,
  mutatingHoldingAcs: Set<string>,
  parentChoice: string
): RawArchivedEvent | null {
  const interfaceId: string | undefined = event.implementedInterfaces[0];
  const shouldIgnoreArchiveEvent =
    !interfaceId ||
    (interfaceId.endsWith("Splice.Api.Token.HoldingV1:Holding") &&
      !mutatingHoldingAcs.has(event.contractId));
  // contract is unknown to the sender or not a holding
  if (shouldIgnoreArchiveEvent) {
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
      actingParties: event.actingParties || [],
    };
  }
}

function toPrettyTransfer(exercisedEvent: any): PrettyTransfer {
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
      senderHoldings: choiceArgument.transfer.holdingCids,
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
  exercisedEvent: any,
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

  const inputHoldings = choiceArgument.inputHoldingCids.map(
    (cid: string, idx: number) => {
      return {
        [cid]: {
          amount: exerciseResult.inputHoldingAmounts[idx],
        },
      };
    }
  );
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
  exercisedEvent?: any;
  archivedEvent?: any;
  createdEvent?: any;
}
function getNodeIdAndEvent(event: any): NodeIdAndEvent {
  if (event.ExercisedEvent)
    return {
      nodeId: event.ExercisedEvent.nodeId,
      exercisedEvent: event.ExercisedEvent,
    };
  else if (event.CreatedEvent)
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
type ExercisedEvent = PrettyTransfer | PrettyBurnMint;
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
