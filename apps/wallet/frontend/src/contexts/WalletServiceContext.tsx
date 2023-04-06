import { Contract, UserStatusResponse } from 'common-frontend';
import { useUserState } from 'common-frontend';
import { Decimal } from 'decimal.js';
import React, { useContext, useMemo } from 'react';
import {
  Middleware,
  createConfiguration,
  WalletApi,
  ServerConfiguration,
  RequestContext,
  ResponseContext,
  CoinPosition,
} from 'wallet-openapi';

import { AppPaymentRequest } from '@daml.js/wallet-payments/lib/CN/Wallet/Payment';
import {
  SubscriptionRequest,
  Subscription,
  SubscriptionIdleState,
  SubscriptionPayment,
} from '@daml.js/wallet-payments/lib/CN/Wallet/Subscriptions';
import { AcceptedTransferOffer, TransferOffer } from '@daml.js/wallet/lib/CN/Wallet/TransferOffer';

const WalletContext = React.createContext<WalletClient | undefined>(undefined);

export interface WalletProps {
  url: string;
}

export interface GetBalanceResponse {
  round: number;
  effectiveUnlockedQty: string;
  effectiveLockedQty: string;
  totalHoldingFees: string;
}

export interface ListResponse {
  lockedCoins: CoinPosition[];
  coins: CoinPosition[];
}

export interface ListTransferOffersResponse {
  offersList: Contract<TransferOffer>[];
}

export interface ListAcceptedTransferOffersResponse {
  acceptedOffersList: Contract<AcceptedTransferOffer>[];
}

export interface ListAppPaymentRequestsResponse {
  paymentRequestsList: Contract<AppPaymentRequest>[];
}

export interface ListSubscriptionRequestsResponse {
  subscriptionRequestsList: Contract<SubscriptionRequest>[];
}

export type SubscriptionTuple = [Contract<Subscription>, SubscriptionState];

export type SubscriptionState =
  | { type: 'idle'; value: Contract<SubscriptionIdleState> }
  | { type: 'payment'; value: Contract<SubscriptionPayment> };

export interface ListSubscriptionsResponse {
  subscriptionsList: SubscriptionTuple[];
}

export interface WalletClient {
  tap: (amount: string) => Promise<void>;
  list: () => Promise<ListResponse>;
  getBalance: () => Promise<GetBalanceResponse>;
  listTransferOffers: () => Promise<ListTransferOffersResponse>;
  createTransferOffer: (
    receiverPartyId: string,
    amount: Decimal,
    description: string,
    expiresAt: Date,
    idempotencyKey: string
  ) => Promise<void>;
  acceptTransferOffer: (offerContractId: string) => Promise<void>;
  withdrawTransferOffer: (offerContractId: string) => Promise<void>;
  rejectTransferOffer: (offerContractId: string) => Promise<void>;
  listAcceptedTransferOffers: () => Promise<ListAcceptedTransferOffersResponse>;

  listAppPaymentRequests: () => Promise<ListAppPaymentRequestsResponse>;
  acceptAppPaymentRequest: (requestContractId: string) => Promise<void>;
  rejectAppPaymentRequest: (requestContractId: string) => Promise<void>;

  listSubscriptionRequests: () => Promise<ListSubscriptionRequestsResponse>;
  acceptSubscriptionRequest: (requestContractId: string) => Promise<void>;
  listSubscriptions: () => Promise<ListSubscriptionsResponse>;
  cancelSubscription: (subscriptionContractId: string) => Promise<void>;

  userStatus: () => Promise<UserStatusResponse>;
  selfGrantFeaturedAppRights: () => Promise<void>;
}

class ApiMiddleware implements Middleware {
  private token: string | undefined;

  async pre(context: RequestContext): Promise<RequestContext> {
    if (!this.token) {
      throw new Error('Request issued before access token was set');
    }
    context.setHeaderParam('Authorization', `Bearer ${this.token}`);
    return context;
  }
  post(context: ResponseContext): Promise<ResponseContext> {
    return Promise.resolve(context);
  }
  constructor(accessToken: string | undefined) {
    this.token = accessToken;
  }
}

export const WalletClientProvider: React.FC<React.PropsWithChildren<WalletProps>> = ({
  url,
  children,
}) => {
  const { userAccessToken } = useUserState();

  // This  is a wrapper around WalletApi, transforming requests and responses into more ergonomic values for the frontend.
  // e.g., OpenAPIContract => Contract<T>
  const friendlyClient: WalletClient | undefined = useMemo(() => {
    const configuration = createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
      promiseMiddleware: [new ApiMiddleware(userAccessToken)],
    });

    const walletClient = new WalletApi(configuration);

    return {
      list: async (): Promise<ListResponse> => {
        const res = await walletClient.list();
        return { coins: res.coins, lockedCoins: res.lockedCoins };
      },
      tap: async amount => {
        const request = { amount: amount };
        await walletClient.tap(request);
      },
      getBalance: async (): Promise<GetBalanceResponse> => {
        const balance = await walletClient.getBalance();
        return {
          round: balance.round,
          effectiveUnlockedQty: balance.effectiveUnlockedQty,
          effectiveLockedQty: balance.effectiveLockedQty,
          totalHoldingFees: balance.totalHoldingFees,
        };
      },
      createTransferOffer: async (
        receiverPartyId,
        amount,
        description,
        expiresAt,
        idempotencyKey
      ) => {
        const request = {
          receiverPartyId: receiverPartyId,
          amount: amount.isInt() ? amount.toFixed(1) : amount.toString(),
          description: description,
          expiresAt: expiresAt.getTime() * 1000,
          idempotencyKey: idempotencyKey,
        };
        await walletClient.createTransferOffer(request);
      },
      listTransferOffers: async (): Promise<ListTransferOffersResponse> => {
        const res = await walletClient.listTransferOffers();
        return {
          offersList: res.offers.map(c => Contract.decodeOpenAPI(c, TransferOffer)),
        };
      },
      acceptTransferOffer: async offerContractId => {
        await walletClient.acceptTransferOffer(offerContractId);
      },
      rejectTransferOffer: async offerContractId => {
        await walletClient.rejectTransferOffer(offerContractId);
      },
      withdrawTransferOffer: async offerContractId => {
        await walletClient.withdrawTransferOffer(offerContractId);
      },
      listAcceptedTransferOffers: async (): Promise<ListAcceptedTransferOffersResponse> => {
        const res = await walletClient.listAcceptedTransferOffers();
        return {
          acceptedOffersList: res.acceptedOffers.map(c =>
            Contract.decodeOpenAPI(c, AcceptedTransferOffer)
          ),
        };
      },

      listAppPaymentRequests: async (): Promise<ListAppPaymentRequestsResponse> => {
        const res = await walletClient.listAppPaymentRequests();
        return {
          paymentRequestsList: res.paymentRequests.map(c =>
            Contract.decodeOpenAPI(c.appPaymentRequest, AppPaymentRequest)
          ),
        };
      },
      acceptAppPaymentRequest: async requestContractId => {
        await walletClient.acceptAppPaymentRequest(requestContractId);
      },
      rejectAppPaymentRequest: async requestContractId => {
        await walletClient.rejectAppPaymentRequest(requestContractId);
      },

      listSubscriptionRequests: async (): Promise<ListSubscriptionRequestsResponse> => {
        const res = await walletClient.listSubscriptionRequests();
        return {
          subscriptionRequestsList: res.subscriptionRequests.map(c =>
            Contract.decodeOpenAPI(c.subscriptionRequest, SubscriptionRequest)
          ),
        };
      },
      acceptSubscriptionRequest: async requestContractId => {
        await walletClient.acceptSubscriptionRequest(requestContractId);
      },
      listSubscriptions: async (): Promise<ListSubscriptionsResponse> => {
        const res = await walletClient.listSubscriptions();
        return {
          subscriptionsList: res.subscriptions.map(sub => {
            const main = Contract.decodeOpenAPI(sub.subscription, Subscription);
            const state = sub.state.payment
              ? {
                  type: 'payment' as 'payment',
                  value: Contract.decodeOpenAPI(sub.state.payment!, SubscriptionPayment),
                }
              : {
                  type: 'idle' as 'idle',
                  value: Contract.decodeOpenAPI(sub.state.idle!, SubscriptionIdleState),
                };
            return [main, state];
          }),
        };
      },
      cancelSubscription: async (subscriptionContractId: string): Promise<void> => {
        await walletClient.cancelSubscriptionRequest(subscriptionContractId);
      },

      userStatus: async () => {
        const res = await walletClient.userStatus();
        return {
          userOnboarded: res.userOnboarded,
          userWalletInstalled: res.userWalletInstalled,
          partyId: res.partyId,
        };
      },
      selfGrantFeaturedAppRights: async () => {
        await walletClient.selfGrantFeatureAppRight();
      },
    };
  }, [url, userAccessToken]);

  return <WalletContext.Provider value={friendlyClient}>{children}</WalletContext.Provider>;
};

export const useWalletClient: () => WalletClient = () => {
  const client = useContext<WalletClient | undefined>(WalletContext);
  if (!client) {
    throw new Error('Wallet client not initialized');
  }
  return client;
};
