// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

/* @ts-expect-error typings unavailable */
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

import { getTomorrowMs, jsonStringDecoder } from '../../utils';
import { HttpClient } from '../http';
import {
  AcceptTransferOfferResponse,
  CreateTransferOfferResponse,
  GetBalanceResponse,
  ListTransferOffersResponse,
  UserStatusResponse,
  acceptTransferOfferResponse,
  createTransferOfferResponse,
  getBalanceResponse,
  listTransferOffersResponse,
  userStatusResponse,
} from './models';

export class ValidatorClient {
  private http: HttpClient;
  private validatorBaseUrl: string;

  private token: string;
  private _partyId: string | undefined;

  private headers = (): Record<string, string> => ({
    'Content-Type': 'application/json',
    Authorization: `Bearer ${this.token}`,
  });

  constructor(validatorBaseUrl: string, token: string) {
    this.validatorBaseUrl = validatorBaseUrl;
    this.token = token;

    this.http = new HttpClient();
  }

  public partyId = (): string | undefined => {
    if (this._partyId) {
      return this._partyId;
    } else {
      const partyId = this.v0.wallet.userStatus()?.party_id;
      this._partyId = partyId; // cache result

      return partyId;
    }
  };

  public v0 = {
    // -*--- VALIDATOR APIS -------------------------------------------------------*-
    register: async (): Promise<void> => {
      try {
        await this.http.post.success(
          `${this.validatorBaseUrl}/api/validator/v0/register`,
          undefined,
          {
            headers: this.headers(),
          },
        );
      } catch (error) {
        throw new Error(`Error registering validator: ${error}`);
      }
    },

    // -*--- WALLET APIS ----------------------------------------------------------*-
    wallet: {
      acceptTransferOffer: (transferOfferCid: string): AcceptTransferOfferResponse | undefined => {
        return this.http.post
          .success(
            `${this.validatorBaseUrl}/api/validator/v0/wallet/token-standard/transfers/${transferOfferCid}/accept`,
            undefined,
            {
              retry: (_, resp) => {
                if (resp?.error_code === 404) {
                  // We wait for the transfer offer to appear first so if we get a 404 there is no point in retrying.
                  return false;
                }
                // Overapproximate everything else and try to retry
                return true;
              },
              headers: this.headers(),
              tags: {
                name: `${this.validatorBaseUrl}/api/validator/v0/wallet/token-standard/transfers/${transferOfferCid}/accept`,
              },
            },
          )
          .then(resp => jsonStringDecoder(acceptTransferOfferResponse, resp.body));
      },
      getBalance: (): GetBalanceResponse | undefined => {
        return this.http.get
          .success(`${this.validatorBaseUrl}/api/validator/v0/wallet/balance`, undefined, {
            headers: this.headers(),
          })
          .then(resp => jsonStringDecoder(getBalanceResponse, resp.body));
      },
      createTransferOffer: (
        amount: string,
        receiver_party_id: string,
      ): CreateTransferOfferResponse | undefined => {
        const tracking_id = uuidv4();

        return this.http.post
          .success(
            `${this.validatorBaseUrl}/api/validator/v0/wallet/token-standard/transfers`,
            JSON.stringify({
              amount,
              tracking_id,
              receiver_party_id,
              description: 'createTransfer from load tester',
              expires_at: getTomorrowMs(),
            }),
            {
              retry: (_, resp) => {
                if (resp?.body?.includes('DUPLICATE_COMMAND')) {
                  // retry will never succeed
                  return false;
                }
                // Overapproximate everything else and try to retry
                return true;
              },
              headers: this.headers(),
            },
          )
          .then(resp => jsonStringDecoder(createTransferOfferResponse, resp.body));
      },
      listTransferOffers: (): ListTransferOffersResponse | undefined => {
        return this.http.get
          .success(
            `${this.validatorBaseUrl}/api/validator/v0/wallet/token-standard/transfers`,
            undefined,
            {
              headers: this.headers(),
            },
          )
          .then(resp => jsonStringDecoder(listTransferOffersResponse, resp.body));
      },
      tap: (amount: string): void => {
        this.http.post.success(
          `${this.validatorBaseUrl}/api/validator/v0/wallet/tap`,
          JSON.stringify({ amount }),
          { retry: false, headers: this.headers() },
        );
      },
      userStatus: (): UserStatusResponse | undefined => {
        return this.http.get
          .success(`${this.validatorBaseUrl}/api/validator/v0/wallet/user-status`, undefined, {
            headers: this.headers(),
          })
          .then(resp => jsonStringDecoder(userStatusResponse, resp.body));
      },
    },
  };
}
