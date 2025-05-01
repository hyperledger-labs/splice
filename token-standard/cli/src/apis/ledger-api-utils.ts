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
  createConfiguration,
  CreatedEvent as LedgerApiCreatedEvent,
  ExercisedEvent as LedgerApiExercisedEvent,
  ArchivedEvent as LedgerApiArchivedEvent,
  DefaultApi as LedgerJsonApi,
  HttpAuthAuthentication,
  JsInterfaceView,
  ServerConfiguration,
  TransactionFilter,
} from "canton-json-api-v2-openapi";

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

// TODO (#18500): handle allocations in such a way that any callers have to handle them too
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
