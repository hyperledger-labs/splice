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
    register: (): void => {
      this.http.post.success(`${this.validatorBaseUrl}/api/validator/v0/register`, undefined, {
        headers: this.headers(),
      });
    },

    // -*--- WALLET APIS ----------------------------------------------------------*-
    wallet: {
      acceptTransferOffer: (transferOfferCid: string): AcceptTransferOfferResponse | undefined => {
        return this.http.post
          .success(
            `${this.validatorBaseUrl}/api/validator/v0/wallet/transfer-offers/${transferOfferCid}/accept`,
            undefined,
            {
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
        return this.http.post
          .success(
            `${this.validatorBaseUrl}/api/validator/v0/wallet/transfer-offers`,
            JSON.stringify({
              amount,
              receiver_party_id,
              description: 'createTransfer from load tester',
              expires_at: getTomorrowMs(),
              tracking_id: uuidv4(),
            }),
            { headers: this.headers() },
          )
          .then(resp => jsonStringDecoder(createTransferOfferResponse, resp.body));
      },
      getTransferOfferStatus: (tracking_id: string): GetTransferOfferStatusResponse | undefined => {
        return this.http.post
          .success(
            `${this.validatorBaseUrl}/api/validator/v0/wallet/transfer-offers/${tracking_id}/status`,
            undefined,
            { headers: this.headers() },
          )
          .then(resp => jsonStringDecoder(getTransferOfferStatusResponse, resp.body));
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
          { headers: this.headers() },
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
