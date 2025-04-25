// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  ensureHoldingViewIsPresent,
  filtersByParty,
  getInterfaceView,
  getMetaKeyValue,
  hasHoldingInterfaceId,
  mergeMetas,
  removeParsedMetaKeys,
} from "../apis/ledger-api-utils";
import {
  BurnedMetaKey,
  HoldingInterface,
  ReasonMetaKey,
  SenderMetaKey,
  TxKindMetaKey,
} from "../constants";
import {
  Holding,
  HoldingsChangeSummary,
  HoldingLock,
  HoldingsChange,
  Label,
  TokenStandardEvent,
  Transaction,
  EmptyHoldingsChangeSummary,
  TokenStandardChoice,
} from "./types";
import BigNumber from "bignumber.js";
import {
  ArchivedEvent as LedgerApiArchivedEvent,
  CreatedEvent as LedgerApiCreatedEvent,
  DefaultApi as LedgerJsonApi,
  Event as LedgerApiEvent,
  ExercisedEvent as LedgerApiExercisedEvent,
  JsGetEventsByContractIdResponse,
  JsTransaction,
} from "canton-json-api-v2-openapi";

// TODO (#18819): handle two-step transfers
export class TransactionParser {
  private readonly ledgerClient: LedgerJsonApi;
  private readonly partyId: string;
  private readonly transaction: JsTransaction;

  constructor(
    transaction: JsTransaction,
    ledgerClient: LedgerJsonApi,
    partyId: string,
  ) {
    this.ledgerClient = ledgerClient;
    this.partyId = partyId;
    this.transaction = transaction;
  }

  async parseTransaction(): Promise<Transaction> {
    const tx = this.transaction;
    const events = await this.parseEvents([...(tx.events || [])].reverse());
    return {
      updateId: tx.updateId,
      offset: tx.offset,
      recordTime: tx.recordTime,
      synchronizerId: tx.synchronizerId,
      events,
    };
  }

  private async parseEvents(
    eventsStack: LedgerApiEvent[],
  ): Promise<TokenStandardEvent[]> {
    let callStack: Array<{ parentChoiceName: string; untilNodeId: number }> =
      [];
    let continueAfterNodeId = -1;
    const result: TokenStandardEvent[] = [];
    while (eventsStack.length > 0) {
      const currentEvent = eventsStack.pop()!;

      const { nodeId, createdEvent, archivedEvent, exercisedEvent } =
        getNodeIdAndEvent(currentEvent);
      callStack = callStack.filter((s) => s.untilNodeId <= nodeId);
      const parentChoice =
        (callStack[callStack.length - 1] &&
          callStack[callStack.length - 1].parentChoiceName) ||
        "none (root node)";

      let parsed: EventParseResult | null;
      if (nodeId <= continueAfterNodeId) {
        parsed = null;
      } else if (createdEvent) {
        parsed = this.parseRawCreate(createdEvent, parentChoice);
      } else if (archivedEvent) {
        parsed = await this.parseRawArchive(archivedEvent, parentChoice);
      } else if (exercisedEvent) {
        parsed = await this.parseExercise(exercisedEvent);
      } else {
        throw new Error(`Impossible event: ${JSON.stringify(currentEvent)}`);
      }

      if (parsed && isLeafEventNode(parsed)) {
        // Exclude events where nothing happened
        if (holdingChangesNonEmpty(parsed.event)) {
          result.push({
            ...parsed.event,
            label: {
              ...parsed.event.label,
              meta: removeParsedMetaKeys(parsed.event.label.meta),
            },
          });
        }
        continueAfterNodeId = parsed.continueAfterNodeId;
      } else if (parsed) {
        callStack.push({
          parentChoiceName: parsed.parentChoiceName,
          untilNodeId: parsed.lastDescendantNodeId,
        });
      }
    }
    return result;
  }

  private parseRawCreate(
    create: LedgerApiCreatedEvent,
    parentChoice: string,
  ): EventParseResult | null {
    const interfaceView = getInterfaceView(create);
    if (!interfaceView || this.partyId !== interfaceView.viewValue.owner) {
      return null;
    }
    const holdingView = ensureHoldingViewIsPresent(create).viewValue;
    const payload: Holding = {
      contractId: create.contractId,
      owner: holdingView.owner,
      instrumentId: holdingView.instrumentId,
      amount: holdingView.amount,
      meta: holdingView.meta,
      lock: holdingView.lock,
    };
    const isLocked = !!holdingView.lock;
    const summary: HoldingsChangeSummary = {
      amountChange: holdingView.amount,
      numInputs: 0,
      inputAmount: "0",
      numOutputs: 1,
      outputAmount: holdingView.amount,
    };
    return {
      continueAfterNodeId: create.nodeId,
      event: {
        label: {
          type: "Create",
          parentChoice,
          contractId: create.contractId,
          offset: create.offset,
          templateId: create.templateId,
          packageName: create.packageName,
          payload,
          meta: undefined,
        },
        unlockedHoldingsChange: {
          creates: isLocked ? [] : [payload],
          archives: [],
        },
        lockedHoldingsChange: {
          creates: isLocked ? [payload] : [],
          archives: [],
        },
        lockedHoldingsChangeSummary: isLocked
          ? summary
          : EmptyHoldingsChangeSummary,
        unlockedHoldingsChangeSummary: isLocked
          ? EmptyHoldingsChangeSummary
          : summary,
      },
    };
  }

  private async parseRawArchive(
    archive: LedgerApiArchivedEvent,
    parentChoice: string,
  ): Promise<EventParseResult | null> {
    if (!hasHoldingInterfaceId(archive)) {
      return null;
    }
    const events = await this.getEventsForArchive(archive);
    if (!events) {
      return null;
    }
    const holdingView = ensureHoldingViewIsPresent(
      events.created.createdEvent,
    ).viewValue;

    const payload: Holding = {
      contractId: archive.contractId,
      owner: holdingView.owner,
      instrumentId: holdingView.instrumentId,
      amount: holdingView.amount,
      meta: holdingView.meta,
      lock: holdingView.lock,
    };
    const isLocked = !!payload.lock;
    const summary: HoldingsChangeSummary = {
      amountChange: holdingView.amount,
      numInputs: 1,
      inputAmount: holdingView.amount,
      numOutputs: 0,
      outputAmount: "0",
    };
    return {
      continueAfterNodeId: archive.nodeId,
      event: {
        label: {
          type: "Archive",
          parentChoice,
          contractId: archive.contractId,
          offset: archive.offset,
          templateId: archive.templateId,
          packageName: archive.packageName,
          actingParties:
            (archive as LedgerApiExercisedEvent).actingParties || [],
          payload,
          meta: undefined,
        },
        unlockedHoldingsChange: {
          archives: isLocked ? [] : [payload],
          creates: [],
        },
        lockedHoldingsChange: {
          archives: isLocked ? [payload] : [],
          creates: [],
        },
        lockedHoldingsChangeSummary: isLocked
          ? summary
          : EmptyHoldingsChangeSummary,
        unlockedHoldingsChangeSummary: isLocked
          ? EmptyHoldingsChangeSummary
          : summary,
      },
    };
  }

  private async parseExercise(
    exercise: LedgerApiExercisedEvent,
  ): Promise<EventParseResult | null> {
    let result: ParsedKnownExercisedEvent | null = null;
    const tokenStandardChoice = {
      name: exercise.choice,
      choiceArgument: exercise.choiceArgument,
      exerciseResult: exercise.exerciseResult,
    };
    switch (exercise.choice) {
      case "TransferFactory_Transfer":
        result = await this.buildTransfer(exercise, tokenStandardChoice);
        break;
      case "BurnMintFactory_BurnMint":
        result = await this.buildMergeSplit(exercise, tokenStandardChoice);
        break;
      default: {
        const meta = mergeMetas(exercise);
        const txKind = getMetaKeyValue(TxKindMetaKey, meta);
        if (txKind) {
          result = await this.parseViaTxKind(exercise, txKind);
        }
        break;
      }
    }
    if (!result) {
      return {
        lastDescendantNodeId: exercise.lastDescendantNodeId,
        parentChoiceName: exercise.choice,
      };
    } else {
      // only this.partyId's holdings should be included in the response
      const lockedHoldingsChange: HoldingsChange = {
        creates: result.children.creates.filter(
          (h) => !!h.lock && h.owner === this.partyId,
        ),
        archives: result.children.archives.filter(
          (h) => !!h.lock && h.owner === this.partyId,
        ),
      };
      const unlockedHoldingsChange: HoldingsChange = {
        creates: result.children.creates.filter(
          (h) => !h.lock && h.owner === this.partyId,
        ),
        archives: result.children.archives.filter(
          (h) => !h.lock && h.owner === this.partyId,
        ),
      };
      return {
        event: {
          label: result.label,
          lockedHoldingsChange,
          lockedHoldingsChangeSummary: computeSummary(
            lockedHoldingsChange,
            this.partyId,
          ),
          unlockedHoldingsChange,
          unlockedHoldingsChangeSummary: computeSummary(
            unlockedHoldingsChange,
            this.partyId,
          ),
        },
        continueAfterNodeId: exercise.lastDescendantNodeId,
      };
    }
  }

  private async parseViaTxKind(
    exercisedEvent: LedgerApiExercisedEvent,
    txKind: string,
  ): Promise<ParsedKnownExercisedEvent | null> {
    switch (txKind) {
      case "transfer":
        return await this.buildTransfer(exercisedEvent, null);
      case "merge-split":
      case "burn":
      case "mint":
        return await this.buildMergeSplit(exercisedEvent, null);
      case "unlock":
        return await this.buildBasic(exercisedEvent, "Unlock", null);
      case "expire-dust":
        return await this.buildBasic(exercisedEvent, "ExpireDust", null);
      default:
        throw new Error(
          `Unknown tx-kind '${txKind}' in ${JSON.stringify(exercisedEvent)}`,
        );
    }
  }

  private async buildTransfer(
    exercisedEvent: LedgerApiExercisedEvent,
    tokenStandardChoice: TokenStandardChoice | null,
  ): Promise<ParsedKnownExercisedEvent | null> {
    const meta = mergeMetas(exercisedEvent);
    const reason = getMetaKeyValue(ReasonMetaKey, meta);
    const sender: string =
      getMetaKeyValue(SenderMetaKey, meta) ||
      exercisedEvent.choiceArgument.transfer.sender;
    if (!sender) {
      console.error(
        `Malformed transfer didn't contain sender. Will instead attempt to parse the children.
        Transfer: ${JSON.stringify(exercisedEvent)}`,
      );
      return null;
    }

    const children = await this.getChildren(exercisedEvent);
    const receiverAmounts = new Map<string, BigNumber>();
    children.creates
      .filter((h) => h.owner !== this.partyId)
      .forEach((holding) =>
        receiverAmounts.set(
          holding.owner,
          (receiverAmounts.get(holding.owner) || BigNumber("0")).plus(
            BigNumber(holding.amount),
          ),
        ),
      );
    const amountChanges = computeAmountChanges(children, meta, this.partyId);

    // TODO (#18819): when supporting two-step transfers, use a better type as opposed to TransferX to aid readability
    let label: Label;
    if (receiverAmounts.size === 0) {
      label = {
        ...amountChanges,
        type: "MergeSplit",
        tokenStandardChoice,
        reason,
        meta,
      };
    } else if (sender === this.partyId) {
      label = {
        ...amountChanges,
        type: "TransferOut",
        receiverAmounts: [...receiverAmounts].map(([k, v]) => {
          return { receiver: k, amount: v.toString() };
        }),
        tokenStandardChoice,
        reason,
        meta,
      };
    } else {
      label = {
        type: "TransferIn",
        // for Transfers, the burn/mint is always 0 for the receiving party (i.e., 0 for TransferIn)
        burnAmount: "0",
        mintAmount: "0",
        sender,
        tokenStandardChoice,
        reason,
        meta,
      };
    }

    return {
      label,
      children,
    };
  }

  private async buildMergeSplit(
    exercisedEvent: LedgerApiExercisedEvent,
    tokenStandardChoice: TokenStandardChoice | null,
  ): Promise<ParsedKnownExercisedEvent> {
    let type: "MergeSplit" | "Mint" | "Burn";
    const meta = mergeMetas(exercisedEvent);
    switch (getMetaKeyValue(TxKindMetaKey, meta)) {
      case "burn":
        type = "Burn";
        break;
      case "mint":
        type = "Mint";
        break;
      default:
        type = "MergeSplit";
    }
    const reason = getMetaKeyValue(ReasonMetaKey, meta);
    const children = await this.getChildren(exercisedEvent);
    const amountChanges = computeAmountChanges(children, meta, this.partyId);

    const label: Label = {
      ...amountChanges,
      type,
      tokenStandardChoice,
      reason,
      meta,
    };

    return {
      label,
      children,
    };
  }

  private async buildBasic(
    exercisedEvent: LedgerApiExercisedEvent,
    type: "Unlock" | "ExpireDust",
    tokenStandardChoice: TokenStandardChoice | null,
  ): Promise<ParsedKnownExercisedEvent> {
    const children = await this.getChildren(exercisedEvent);
    const meta = mergeMetas(exercisedEvent);
    const amountChanges = computeAmountChanges(children, meta, this.partyId);
    const reason = getMetaKeyValue(ReasonMetaKey, meta);
    return {
      label: {
        ...amountChanges,
        type,
        tokenStandardChoice,
        reason,
        meta,
      },
      children,
    };
  }

  private async getChildren(
    exercisedEvent: LedgerApiExercisedEvent,
  ): Promise<HoldingsChange> {
    const mutatingResult: HoldingsChange = { creates: [], archives: [] };
    const childrenEventsSlice = (this.transaction.events || [])
      .map(getNodeIdAndEvent)
      .filter(
        ({ nodeId }) =>
          nodeId > exercisedEvent.nodeId &&
          nodeId <= exercisedEvent.lastDescendantNodeId,
      );

    if (exercisedEvent.consuming && hasHoldingInterfaceId(exercisedEvent)) {
      const selfEvent = await this.getEventsForArchive(exercisedEvent);
      if (selfEvent) {
        const holdingView = ensureHoldingViewIsPresent(
          selfEvent.created.createdEvent,
        ).viewValue;
        mutatingResult.archives.push({
          amount: holdingView.amount,
          instrumentId: holdingView.instrumentId,
          contractId: exercisedEvent.contractId,
          owner: holdingView.owner,
          meta: holdingView.meta,
          lock: holdingView.lock,
        });
      }
    }

    for (const {
      createdEvent,
      archivedEvent,
      exercisedEvent,
    } of childrenEventsSlice) {
      if (createdEvent) {
        const interfaceView = getInterfaceView(createdEvent);
        if (interfaceView) {
          const holdingView = interfaceView.viewValue;
          mutatingResult.creates.push({
            amount: holdingView.amount,
            instrumentId: holdingView.instrumentId,
            contractId: createdEvent.contractId,
            owner: holdingView.owner,
            meta: holdingView.meta,
            lock: holdingView.lock,
          });
        }
      } else if (
        (archivedEvent && hasHoldingInterfaceId(archivedEvent)) ||
        (exercisedEvent &&
          exercisedEvent.consuming &&
          hasHoldingInterfaceId(exercisedEvent))
      ) {
        const contractEvents = await this.getEventsForArchive(
          archivedEvent || exercisedEvent!,
        );
        if (contractEvents) {
          const holdingView = ensureHoldingViewIsPresent(
            contractEvents.created?.createdEvent,
          ).viewValue;
          mutatingResult.archives.push({
            amount: holdingView.amount,
            instrumentId: holdingView.instrumentId,
            contractId: archivedEvent?.contractId || exercisedEvent!.contractId,
            owner: holdingView.owner,
            meta: holdingView.meta,
            lock: holdingView.lock,
          });
        }
      }
    }

    return {
      // remove transient contracts
      creates: mutatingResult.creates.filter(
        (create) =>
          !mutatingResult.archives.some(
            (archive) => create.contractId === archive.contractId,
          ),
      ),
      archives: mutatingResult.archives.filter(
        (archive) =>
          !mutatingResult.creates.some(
            (create) => create.contractId === archive.contractId,
          ),
      ),
    };
  }

  private async getEventsForArchive(
    archivedEvent: LedgerApiArchivedEvent | LedgerApiExercisedEvent,
  ): Promise<null | Required<JsGetEventsByContractIdResponse>> {
    if (!(archivedEvent.witnessParties || []).includes(this.partyId)) {
      return null;
    }
    const events = await this.ledgerClient
      .postV2EventsEventsByContractId({
        contractId: archivedEvent.contractId,
        eventFormat: {
          filtersByParty: filtersByParty(
            this.partyId,
            [HoldingInterface],
            true,
          ),
          verbose: false,
        },
        requestingParties: [],
      })
      .catch((err) => {
        // This will happen for holdings with consuming choices
        // where the party the script is running on is an actor on the choice
        // but not a stakeholder.
        if (err.code === 404) {
          return null;
        } else {
          throw err;
        }
      });
    if (!events) {
      return null;
    }
    const created = events.created;
    const archived = events.archived;
    if (!created || !archived) {
      throw new Error(
        `Archival of ${
          archivedEvent.contractId
        } does not have a corresponding create/archive event: ${JSON.stringify(
          events,
        )}`,
      );
    }
    return { created, archived };
  }
}

type EventParseResult = ParseChildren | ParsedEvent;
function isLeafEventNode(result: EventParseResult): result is ParsedEvent {
  return !!(result as ParsedEvent).event;
}
interface ParsedEvent {
  event: TokenStandardEvent;
  continueAfterNodeId: number;
}
interface ParseChildren {
  parentChoiceName: string;
  lastDescendantNodeId: number;
}

interface ParsedKnownExercisedEvent {
  label: Label;
  children: HoldingsChange;
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
  } else if (event.CreatedEvent) {
    return {
      nodeId: event.CreatedEvent.nodeId,
      createdEvent: event.CreatedEvent,
    };
  } else if (event.ArchivedEvent) {
    return {
      nodeId: event.ArchivedEvent.nodeId,
      archivedEvent: event.ArchivedEvent,
    };
  } else {
    throw new Error(`Impossible event type: ${event}`);
  }
}

function sumHoldingsChange(
  change: HoldingsChange,
  filter: (owner: string, lock: HoldingLock | null) => boolean,
): BigNumber {
  return sumHoldings(
    change.creates.filter((create) => filter(create.owner, create.lock)),
  ).minus(
    sumHoldings(
      change.archives.filter((archive) => filter(archive.owner, archive.lock)),
    ),
  );
}

function sumHoldings(holdings: Holding[]): BigNumber {
  return BigNumber.sum(
    ...holdings.map((h) => h.amount).concat(["0"]), // avoid NaN
  );
}

function computeAmountChanges(
  children: HoldingsChange,
  meta: any,
  partyId: string,
) {
  const burnAmount = BigNumber(getMetaKeyValue(BurnedMetaKey, meta) || "0");
  const partyHoldingAmountChange = sumHoldingsChange(
    children,
    (owner) => owner === partyId,
  );
  const otherPartiesHoldingAmountChange = sumHoldingsChange(
    children,
    (owner) => owner !== partyId,
  );
  const mintAmount = partyHoldingAmountChange
    .plus(burnAmount)
    .plus(otherPartiesHoldingAmountChange);
  return {
    burnAmount: burnAmount.toString(),
    mintAmount: mintAmount.toString(),
  };
}

function computeSummary(
  changes: HoldingsChange,
  partyId: string,
): HoldingsChangeSummary {
  const amountChange = sumHoldingsChange(changes, (owner) => owner === partyId);
  const outputAmount = sumHoldings(changes.creates);
  const inputAmount = sumHoldings(changes.archives);
  return {
    amountChange: amountChange.toString(),
    numOutputs: changes.creates.length,
    outputAmount: outputAmount.toString(),
    numInputs: changes.archives.length,
    inputAmount: inputAmount.toString(),
  };
}

function holdingChangesNonEmpty(event: TokenStandardEvent): boolean {
  return (
    event.unlockedHoldingsChange.creates.length > 0 ||
    event.unlockedHoldingsChange.archives.length > 0 ||
    event.lockedHoldingsChange.creates.length > 0 ||
    event.lockedHoldingsChange.archives.length > 0
  );
}
