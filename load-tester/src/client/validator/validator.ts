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
  GetTransferOfferStatusResponse,
  ListTransactionsResponse,
  ListTransferOffersResponse,
  UserStatusResponse,
  acceptTransferOfferResponse,
  createTransferOfferResponse,
  getBalanceResponse,
  getTransferOfferStatusResponse,
  listTransactionsResponse,
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
      acceptTransferOffer: (
        transferOfferCid: string,
        trackingId: string,
      ): AcceptTransferOfferResponse | undefined => {
        return this.http.post
          .success(
            `${this.validatorBaseUrl}/api/validator/v0/wallet/transfer-offers/${transferOfferCid}/accept`,
            undefined,
            {
              retry: (_, resp) => {
                if (resp?.error_code) {
                  // represents 4xx, 5xx, and non-http errors (https://grafana.com/docs/k6/latest/javascript-api/error-codes/)
                  // we need to check if the accept actually went through on the backend via the status endpoint.
                  const status = this.v0.wallet.getTransferOfferStatus(trackingId);

                  if (!status) {
                    // if this API request _also_ fails to respond, things are probably going pretty bad,
                    // so disable this request's retries to avoid adding to the fire
                    return false;
                  }

                  return status.status === 'created';
                }

                return true;
              },
              headers: this.headers(),
              tags: {
                name: `${this.validatorBaseUrl}/api/validator/v0/wallet/transfer-offers/$transferOfferCid/accept`,
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
            `${this.validatorBaseUrl}/api/validator/v0/wallet/transfer-offers`,
            JSON.stringify({
              amount,
              tracking_id,
              receiver_party_id,
              description: 'createTransfer from load tester',
              expires_at: getTomorrowMs(),
            }),
            {
              retry: (_, resp) => {
                if (resp?.status === 409) {
                  // backend telling us this is a duplicate create request, explicitly do not retry
                  return false;
                }
                if (resp?.error_code === 1050) {
                  // this code represents a request timeout from k6 (https://grafana.com/docs/k6/latest/javascript-api/error-codes/)
                  // we need to check if the create actually went through on the backend via the status endpoint.
                  const status = this.v0.wallet.getTransferOfferStatus(tracking_id);

                  if (!status) {
                    // if this API request _also_ fails to respond, things are probably going pretty bad,
                    // so disable this request's retries to avoid adding to the fire
                    return false;
                  }

                  return status.status === 'not found';
                }

                return true;
              },
              headers: this.headers(),
            },
          )
          .then(resp => jsonStringDecoder(createTransferOfferResponse, resp.body));
      },
      getTransferOfferStatus: (
        tracking_id: string,
      ): GetTransferOfferStatusResponse | { status: 'not found' } | undefined => {
        return this.http.post
          .success(
            `${this.validatorBaseUrl}/api/validator/v0/wallet/transfer-offers/${tracking_id}/status`,
            undefined,
            {
              headers: this.headers(),
              tags: {
                name: `${this.validatorBaseUrl}/api/validator/v0/wallet/transfer-offers/$tracking_id/status`,
              },
            },
          )
          .then(resp => {
            if (resp.status === 404) {
              return { status: 'not found' };
            }
            return jsonStringDecoder(getTransferOfferStatusResponse, resp.body);
          });
      },
      listTransactions: (): ListTransactionsResponse | undefined => {
        return this.http.post
          .success(
            `${this.validatorBaseUrl}/api/validator/v0/wallet/transactions`,
            JSON.stringify({ pageSize: 10 }),
            { headers: this.headers() },
          )
          .then(resp => jsonStringDecoder(listTransactionsResponse, resp.body));
      },
      listTransferOffers: (): ListTransferOffersResponse | undefined => {
        return this.http.get
          .success(`${this.validatorBaseUrl}/api/validator/v0/wallet/transfer-offers`, undefined, {
            headers: this.headers(),
          })
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
