// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { CommandOptions } from "../cli";
import { AllKnownMetaKeys } from "../constants";
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
    })
  );
}

export function filtersByParty(
  party: string,
  interfaceNames: string[],
  includeWildcard: boolean
): TransactionFilter["filtersByParty"] {
  return {
    [party]: {
      cumulative: interfaceNames
        .map((interfaceName) => {
          return {
            identifierFilter: {
              InterfaceFilter: {
                value: {
                  interfaceId: interfaceName,
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
            : []
        ),
    },
  };
}

export function hasHoldingInterfaceId(
  event: LedgerApiExercisedEvent | LedgerApiArchivedEvent
): boolean {
  return (event.implementedInterfaces || []).some((interfaceId) =>
    interfaceId.endsWith("Splice.Api.Token.HoldingV1:Holding")
  );
}

export function getInterfaceView(
  createdEvent: LedgerApiCreatedEvent
): JsInterfaceView | null {
  const interfaceViews = createdEvent.interfaceViews || null;
  return (interfaceViews && interfaceViews[0]) || null;
}

// TODO (#18500): handle allocations in such a way that any callers have to handle them too
/**
 * Use this when `createdEvent` is guaranteed to have a Holding interface view because the ledger api filters
 * include it, and thus is guaranteed to be returned by the API.
 */
export function ensureHoldingViewIsPresent(
  createdEvent: LedgerApiCreatedEvent
): JsInterfaceView {
  const interfaceView = getInterfaceView(createdEvent);
  if (!interfaceView) {
    throw new Error(
      `Expected to have interface views, but didn't: ${JSON.stringify(
        createdEvent
      )}`
    );
  }
  if (
    !interfaceView.interfaceId.endsWith("Splice.Api.Token.HoldingV1:Holding")
  ) {
    throw new Error(
      `Not a Holding but a ${interfaceView.interfaceId}: ${JSON.stringify(
        createdEvent
      )}`
    );
  }
  return interfaceView;
}

type Meta = { values: {[key: string]: string;} } | undefined;

export function mergeMetas(event: LedgerApiExercisedEvent): Meta {
  const lastWriteWins = [
    event.choiceArgument?.transfer?.meta,
    event.choiceArgument?.extraArgs?.meta,
    event.choiceArgument?.meta,
    event.exerciseResult?.meta,
  ];
  const result: { [key: string]: string; } = {};
  lastWriteWins.forEach((meta) => {
    const values: {[key:string]: string } = meta?.values || [];
    Object.entries(values).forEach(([k, v]) => {result[k] = v;});
  });
  if (Object.keys(result).length === 0) return undefined;
  // order of keys doesn't matter, but we return it consistent for test purposes (and it's nicer)
  else return { values: result };
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
    values: Object.fromEntries(Object.entries(meta?.values || {}).filter(
      ([k, _]) => !AllKnownMetaKeys.includes(k)
    )),
  };
}
