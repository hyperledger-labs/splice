// import http, { RefinedResponse } from 'k6/http';
import { z } from 'zod';

import { HttpClient } from '../utils/http';

const userStatusResponse = z.object({
  party_id: z.string(),
  user_onboarded: z.boolean(),
  user_wallet_installed: z.boolean(),
  has_featured_app_right: z.boolean(),
});

type UserStatusResponse = z.infer<typeof userStatusResponse>;

const parseJsonBody = <Z extends z.ZodTypeAny = z.ZodNever>(
  schema: Z,
  body: string,
): z.infer<Z> | undefined => {
  const result = schema.safeParse(JSON.parse(body));
  if (result.success) {
    return result.data;
  } else {
    return undefined;
  }
};

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

    this.http = new HttpClient(validatorBaseUrl);
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
      this.http.post.success(
        `${this.validatorBaseUrl}/api/validator/v0/register`,
        undefined,
        this.headers(),
        () => {},
      );
    },

    // -*--- WALLET APIS ----------------------------------------------------------*-
    wallet: {
      tap: (amount: string): void => {
        this.http.post.success(
          `${this.validatorBaseUrl}/api/validator/v0/wallet/tap`,
          JSON.stringify({ amount }),
          this.headers(),
          () => {},
        );
      },
      userStatus: (): UserStatusResponse | undefined => {
        return this.http.get.success(
          `${this.validatorBaseUrl}/api/validator/v0/wallet/user-status`,
          undefined,
          this.headers(),
          resp => parseJsonBody(userStatusResponse, resp.body),
        );
      },
    },
  };
}
