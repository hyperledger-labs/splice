// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// TODO (#18142): the types are not nice, but this is just temporary until we can replace it with openapi-generated code
export class LedgerClient {
  readonly baseUrl: string;
  readonly token: string;

  constructor(baseUrl: string, accessToken: string) {
    this.token = accessToken;
    this.baseUrl = baseUrl;
  }

  private async doFetch<T>(
    path: string,
    method: string,
    body?: object
  ): Promise<T> {
    const fullUrl = `${this.baseUrl}${path}`;
    const response = await fetch(fullUrl, {
      method,
      body: body && JSON.stringify(body),
      headers: { Authorization: `Bearer ${this.token}` },
    });

    if (response.ok) {
      return await response.json();
    } else {
      const txt = await response.text();
      throw new Error(
        `Obtained non-OK response from ${fullUrl}: ${response.statusText}.\n${txt}`
      );
    }
  }

  async getLedgerEnd(): Promise<GetLedgerEndResponse> {
    return await this.doFetch("/v2/state/ledger-end", "GET");
  }

  // This currently (11.03.2025) does not support pagination. It returns the ACS up to a server-defined limit.
  async getActiveContractsOfParty(
    party: string,
    activeAtOffset: string,
    interfaceNames: string[]
  ): Promise<any[]> {
    return await this.doFetch("/v2/state/active-contracts", "POST", {
      filter: {
        filtersByParty: this.filtersByParty(party, interfaceNames, false),
      },
      verbose: false,
      activeAtOffset: activeAtOffset,
    });
  }

  async getLatestPrunedOffsets(): Promise<LatestPrunedOffsets> {
    return await this.doFetch("/v2/state/latest-pruned-offsets", "GET");
  }

  async getUpdates(
    party: string,
    interfaceNames: string[],
    fromOffset: string
  ): Promise<any[]> {
    return await this.doFetch("/v2/updates/flats", "POST", {
      updateFormat: {
        includeTransactions: {
          eventFormat: {
            filtersByParty: this.filtersByParty(party, interfaceNames, true),
            verbose: false,
          },
          transactionShape: { TRANSACTION_SHAPE_LEDGER_EFFECTS: {} },
        },
      },
      beginExclusive: fromOffset,
      verbose: false,
    });
  }

  private filtersByParty(
    party: string,
    interfaceNames: string[],
    includeWildcard: boolean
  ) {
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
}

interface GetLedgerEndResponse {
  offset: string;
}

interface LatestPrunedOffsets {
  participantPrunedUpToInclusive: string;
  allDivulgedContractsPrunedUpToInclusive: string;
}
