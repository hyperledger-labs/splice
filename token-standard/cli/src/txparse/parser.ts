// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import BigNumber from "bignumber.js";
import {
  ArchivedEvent as LedgerApiArchivedEvent, CreatedEvent as LedgerApiCreatedEvent, DefaultApi as LedgerJsonApi, Event as LedgerApiEvent,
  ExercisedEvent as LedgerApiExercisedEvent, JsGetEventsByContractIdResponse, JsTransaction
} from "canton-json-api-v2-openapi";
import {
  ensureHoldingViewIsPresent,
  filtersByParty,
  getInterfaceView,
  getMetaKeyValue,
  isHoldingInterfaceId,
  removeParsedMetaKeys
} from "../apis/ledger-api-utils";
import {
  HoldingInterface,
  ReasonMetaKey,
  SenderMetaKey,
  TxKindMetaKey
} from "../constants";
import {
  Holding,
  HoldingsChange,
  Label, TokenStandardEvent, Transaction
} from "./types";

// TODO (#18819): handle two-step transfers
export class TransactionParser {
  private readonly ledgerClient: LedgerJsonApi;
  private readonly partyId: string;
  private readonly transaction: JsTransaction;

  constructor(
    transaction: JsTransaction,
    ledgerClient: LedgerJsonApi,
    partyId: string
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
    eventsStack: LedgerApiEvent[]
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

      if (!parsed || isLeafEventNode(parsed)) {
        if (
          parsed &&
          (parsed.event.holdingsChange.creates.length > 0 ||
            parsed.event.holdingsChange.archives.length > 0)
        ) {
          result.push({
            label: Object.assign(parsed.event.label, {
              meta: removeParsedMetaKeys(parsed.event.label.meta),
            }),
            // only this partyId's holdings are relevant for display
            holdingsChange: {
              creates: parsed.event.holdingsChange.creates.filter(
                (h) => h.owner === this.partyId
              ),
              archives: parsed.event.holdingsChange.archives.filter(
                (h) => h.owner === this.partyId
              ),
            },
          });
        }
        continueAfterNodeId =
          parsed?.continueAfterNodeId || continueAfterNodeId;
      } else {
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
    parentChoice: string
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
    return {
      continueAfterNodeId: create.nodeId,
      event: {
        label: {
          type: "RawCreate",
          parentChoice,
          contractId: create.contractId,
          offset: create.offset,
          templateId: create.templateId,
          packageName: create.packageName,
          payload,
          meta: undefined,
        },
        holdingsChange: {
          creates: [payload],
          archives: [],
        },
      },
    };
  }

  private async parseRawArchive(
    archive: LedgerApiArchivedEvent,
    parentChoice: string
  ): Promise<EventParseResult | null> {
    const events = await this.getEventsForArchive(archive);
    if (!events) {
      return null;
    }
    const holdingView = ensureHoldingViewIsPresent(
      events.created.createdEvent
    ).viewValue;

    const payload: Holding = {
      contractId: archive.contractId,
      owner: holdingView.owner,
      instrumentId: holdingView.instrumentId,
      amount: holdingView.amount,
      meta: holdingView.meta,
      lock: holdingView.lock,
    };
    return {
      continueAfterNodeId: archive.nodeId,
      event: {
        label: {
          type: "RawArchive",
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
        holdingsChange: {
          creates: [],
          archives: [payload],
        },
      },
    };
  }

  private async parseExercise(
    exercise: LedgerApiExercisedEvent
  ): Promise<EventParseResult | null> {
    switch (exercise.choice) {
      case "TransferFactory_Transfer":
        return {
          event: await this.buildTransfer(exercise),
          continueAfterNodeId: exercise.lastDescendantNodeId,
        };
      case "BurnMintFactory_BurnMint":
        return {
          event: await this.buildBurnMint(exercise),
          continueAfterNodeId: exercise.lastDescendantNodeId,
        };
      default:
        const meta = exercise.exerciseResult.meta;
        const txKind = getMetaKeyValue(TxKindMetaKey, meta);
        if (txKind) {
          return this.parseViaTxKind(exercise, txKind);
        } else {
          return {
            lastDescendantNodeId: exercise.nodeId,
            parentChoiceName: exercise.choice,
          };
        }
    }
  }

  private async parseViaTxKind(
    exercisedEvent: LedgerApiExercisedEvent,
    txKind: string
  ): Promise<EventParseResult | null> {
    switch (txKind) {
      case "transfer":
        return {
          event: await this.buildTransfer(exercisedEvent),
          continueAfterNodeId: exercisedEvent.lastDescendantNodeId,
        };
      case "merge-split":
      case "burn":
      case "mint":
        return {
          event: await this.buildBurnMint(exercisedEvent),
          continueAfterNodeId: exercisedEvent.lastDescendantNodeId,
        };
      // TODO (#18819): implement these & add test data to make sure they're tested
      case "unlock":
        return null;
      case "expire-dust":
        return null;
      default:
        throw new Error(
          `Unknown tx-kind '${txKind}' in ${JSON.stringify(exercisedEvent)}`
        );
    }
  }

  private async buildTransfer(
    exercisedEvent: LedgerApiExercisedEvent
  ): Promise<TokenStandardEvent> {
    const meta = exercisedEvent.exerciseResult.meta;
    const sender: string =
      getMetaKeyValue(SenderMetaKey, meta) ||
      exercisedEvent.choiceArgument.transfer.sender;
    if (!sender) {
      throw new Error(
        `Malformed transfer didn't contain sender: ${JSON.stringify(
          exercisedEvent
        )}`
      );
    }

    const children = await this.getChildren(exercisedEvent);
    const senderAmount = sumHoldingsChange(
      children,
      (owner) => owner === this.partyId
    );
    const receiverAmounts = new Map<string, BigNumber>();
    children.creates
      .filter((h) => h.owner !== this.partyId)
      .forEach((holding) =>
        receiverAmounts.set(
          holding.owner,
          (receiverAmounts.get(holding.owner) || BigNumber("0")).plus(
            BigNumber(holding.amount)
          )
        )
      );

    let label: Label;
    if (sender === this.partyId) {
      const isSplit = children.creates
        .concat(children.archives)
        .every((holding) => holding.owner === this.partyId);
      if (isSplit) {
        label = {
          type: "Split",
          meta,
        };
      } else {
        label = {
          type: "TransferOut",
          receiverAmounts: [...receiverAmounts].map(([k, v]) => {
            return { receiver: k, amount: v.toString() };
          }),
          senderAmount: senderAmount.toString(),
          meta,
        };
      }
    } else {
      label = {
        type: "TransferIn",
        sender,
        amount: senderAmount.toString(),
        meta,
      };
    }

    return {
      label,
      holdingsChange: children,
    };
  }

  private async buildBurnMint(
    exercisedEvent: LedgerApiExercisedEvent
  ): Promise<TokenStandardEvent> {
    const meta = exercisedEvent.exerciseResult.meta;
    const reason = getMetaKeyValue(ReasonMetaKey, meta) || null;
    const children = await this.getChildren(exercisedEvent);

    let label: Label;
    if (children.creates.length >= 0 && children.archives.length === 0) {
      label = {
        type: "Mint",
        reason,
        meta,
      };
    } else if (children.archives.length >= 0 && children.creates.length === 0) {
      label = {
        type: "Burn",
        reason,
        meta,
      };
    } else {
      label = {
        type: "CombinedBurnMint",
        reason,
        meta,
      };
    }

    return {
      label,
      holdingsChange: children,
    };
  }

  private async getChildren(
    exercisedEvent: LedgerApiExercisedEvent
  ): Promise<HoldingsChange> {
    const mutatingResult: HoldingsChange = { creates: [], archives: [] };
    const childrenEventsSlice = (this.transaction.events || [])
      .map(getNodeIdAndEvent)
      .filter(
        ({ nodeId }) =>
          nodeId > exercisedEvent.nodeId &&
          nodeId <= exercisedEvent.lastDescendantNodeId
      );

    if (
      exercisedEvent.consuming &&
      isHoldingInterfaceId(exercisedEvent.interfaceId)
    ) {
      const selfEvent = await this.getEventsForArchive(exercisedEvent);
      if (selfEvent) {
        const holdingView = ensureHoldingViewIsPresent(
          selfEvent.created.createdEvent
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

    for (const { createdEvent, archivedEvent } of childrenEventsSlice) {
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
      } else if (archivedEvent) {
        const contractEvents = await this.getEventsForArchive(archivedEvent);
        if (contractEvents) {
          const holdingView = ensureHoldingViewIsPresent(
            contractEvents.created?.createdEvent
          ).viewValue;
          mutatingResult.archives.push({
            amount: holdingView.amount,
            instrumentId: holdingView.instrumentId,
            contractId: archivedEvent.contractId,
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
            (archive) => create.contractId === archive.contractId
          )
      ),
      archives: mutatingResult.archives.filter(
        (archive) =>
          !mutatingResult.creates.some(
            (create) => create.contractId === archive.contractId
          )
      ),
    };
  }

  private async getEventsForArchive(
    archivedEvent: LedgerApiArchivedEvent | LedgerApiExercisedEvent
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
            true
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
          events
        )}`
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
  else throw new Error(`Impossible event type: ${event}`);
}

function sumHoldingsChange(
  change: HoldingsChange,
  filter: (owner: string) => boolean
): BigNumber {
  return sumHoldings(
    change.creates.filter((create) => filter(create.owner))
  ).minus(
    sumHoldings(change.archives.filter((archive) => filter(archive.owner)))
  );
}

function sumHoldings(holdings: Holding[]): BigNumber {
  return BigNumber.sum(
    ...holdings.map((h) => h.amount).concat(["0"]) // avoid NaN
  );
}
