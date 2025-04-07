// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { CommandOptions } from "../cli";
import {
  createConfiguration,
  DefaultApi as LedgerJsonApi,
  HttpAuthAuthentication,
  ServerConfiguration,
  TransactionFilter,
  JsInterfaceView,
  CreatedEvent as LedgerApiCreatedEvent,
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

type HoldingView = JsInterfaceView["viewValue"];

/**
 * Use this when `createdEvent` is guaranteed to have a Holding interface view because the ledger api filters
 * include it, and thus is guaranteed to be returned by the API.
 */
export function ensureHoldingViewIsPresent(
  createdEvent: LedgerApiCreatedEvent
): JsInterfaceView {
  const interfaceViews = createdEvent.interfaceViews;
  if (!interfaceViews || !interfaceViews[0]) {
    throw new Error(
      `CreatedEvent has no interface views. They should be included in all ledger-api responses. CreatedEvent: ${JSON.stringify(
        createdEvent
      )}`
    );
  }
  return interfaceViews[0];
}
