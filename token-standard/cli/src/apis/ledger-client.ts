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
    interface_name: string
  ): Promise<any[]> {
    const payload = {
      filter: {
        filtersByParty: {
          [party]: {
            cumulative: [
              {
                identifierFilter: {
                  InterfaceFilter: {
                    value: {
                      interfaceId: interface_name,
                      includeInterfaceView: true,
                      includeCreatedEventBlob: true,
                    },
                  },
                },
              },
            ],
          },
        },
      },
      verbose: false,
      activeAtOffset: activeAtOffset,
    };
    return await this.doFetch("/v2/state/active-contracts", "POST", payload);
  }
}

interface GetLedgerEndResponse {
  offset: string;
}
