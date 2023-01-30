import * as v0 from 'common-protobuf/com/daml/network/wallet/v0/wallet_service_pb';
import { Contract, UserStatusResponse } from 'common-frontend';
import { useUserState } from 'common-frontend';
import { WalletServicePromiseClient } from 'common-protobuf/com/daml/network/wallet/v0/wallet_service_grpc_web_pb';
import { Decimal } from 'decimal.js';
import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
import { Metadata } from 'grpc-web';
import React, { useContext, useMemo } from 'react';

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
  lockedCoins: v0.CoinPosition[];
  coins: v0.CoinPosition[];
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
}

interface Credentials extends Metadata {
  Authorization: string;
}

export const WalletClientProvider: React.FC<React.PropsWithChildren<WalletProps>> = ({
  url,
  children,
}) => {
  const { userAccessToken } = useUserState();

  // Collection of methods that wrap the raw grpc codegen client methods because everything in that API is constructor-chain based, requires auth headers, and just generally awkward to use
  const friendlyClient: WalletClient | undefined = useMemo(() => {
    const getCreds = (): Credentials => {
      if (!userAccessToken) {
        throw new Error('Request issued before access token was set');
      }
      return {
        Authorization: `Bearer ${userAccessToken}`,
      };
    };

    const walletClient = new WalletServicePromiseClient(url, null, null);

    return {
      list: async (): Promise<ListResponse> => {
        const res = await walletClient.list(new v0.ListRequest(), getCreds());
        return { coins: res.getCoinsList(), lockedCoins: res.getLockedCoinsList() };
      },
      tap: async amount => {
        await walletClient.tap(new v0.TapRequest().setAmount(amount), getCreds());
      },
      getBalance: async (): Promise<GetBalanceResponse> => {
        const balance = await walletClient.getBalance(new v0.GetBalanceRequest(), getCreds());
        return {
          round: balance.getRound(),
          effectiveUnlockedQty: balance.getEffectiveUnlockedQty(),
          effectiveLockedQty: balance.getEffectiveLockedQty(),
          totalHoldingFees: balance.getTotalHoldingFees(),
        };
      },
      createTransferOffer: async (
        receiverPartyId,
        amount,
        description,
        expiresAt,
        idempotencyKey
      ) => {
        await walletClient.createTransferOffer(
          new v0.CreateTransferOfferRequest()
            .setReceiverPartyId(receiverPartyId)
            .setAmount(amount.isInt() ? amount.toFixed(1) : amount.toString())
            .setDescription(description)
            .setExpiresAt(expiresAt.getTime() * 1000)
            .setIdempotencyKey(idempotencyKey),
          getCreds()
        );
      },
      listTransferOffers: async (): Promise<ListTransferOffersResponse> => {
        const res = await walletClient.listTransferOffers(new Empty(), getCreds());
        return {
          offersList: res.getOffersList().map(c => Contract.decode(c, TransferOffer)),
        };
      },
      acceptTransferOffer: async offerContractId => {
        await walletClient.acceptTransferOffer(
          new v0.AcceptTransferOfferRequest().setOfferContractId(offerContractId),
          getCreds()
        );
      },
      rejectTransferOffer: async offerContractId => {
        await walletClient.rejectTransferOffer(
          new v0.RejectTransferOfferRequest().setOfferContractId(offerContractId),
          getCreds()
        );
      },
      withdrawTransferOffer: async offerContractId => {
        await walletClient.withdrawTransferOffer(
          new v0.WithdrawTransferOfferRequest().setOfferContractId(offerContractId),
          getCreds()
        );
      },
      listAcceptedTransferOffers: async (): Promise<ListAcceptedTransferOffersResponse> => {
        const res = await walletClient.listAcceptedTransferOffers(new Empty(), getCreds());
        return {
          acceptedOffersList: res
            .getAcceptedOffersList()
            .map(c => Contract.decode(c, AcceptedTransferOffer)),
        };
      },

      listAppPaymentRequests: async (): Promise<ListAppPaymentRequestsResponse> => {
        const res = await walletClient.listAppPaymentRequests(
          new v0.ListAppPaymentRequestsRequest(),
          getCreds()
        );
        return {
          paymentRequestsList: res
            .getPaymentRequestsList()
            .map(c => Contract.decode(c, AppPaymentRequest)),
        };
      },
      acceptAppPaymentRequest: async requestContractId => {
        await walletClient.acceptAppPaymentRequest(
          new v0.AcceptAppPaymentRequestRequest().setRequestContractId(requestContractId),
          getCreds()
        );
      },
      rejectAppPaymentRequest: async requestContractId => {
        await walletClient.rejectAppPaymentRequest(
          new v0.RejectAppPaymentRequestRequest().setRequestContractId(requestContractId),
          getCreds()
        );
      },

      listSubscriptionRequests: async (): Promise<ListSubscriptionRequestsResponse> => {
        const res = await walletClient.listSubscriptionRequests(
          new v0.ListSubscriptionRequestsRequest(),
          getCreds()
        );
        return {
          subscriptionRequestsList: res
            .getSubscriptionRequestsList()
            .map(c => Contract.decode(c, SubscriptionRequest)),
        };
      },
      acceptSubscriptionRequest: async requestContractId => {
        await walletClient.acceptSubscriptionRequest(
          new v0.AcceptSubscriptionRequestRequest().setRequestContractId(requestContractId),
          getCreds()
        );
      },
      listSubscriptions: async (): Promise<ListSubscriptionsResponse> => {
        const res = await walletClient.listSubscriptions(
          new v0.ListSubscriptionsRequest(),
          getCreds()
        );
        return {
          subscriptionsList: res.getSubscriptionsList().map(sub => {
            const main = Contract.decode(sub.getMain()!, Subscription);
            const state = sub.hasPayment()
              ? {
                  type: 'payment' as 'payment',
                  value: Contract.decode(sub.getPayment()!, SubscriptionPayment),
                }
              : {
                  type: 'idle' as 'idle',
                  value: Contract.decode(sub.getIdle()!, SubscriptionIdleState),
                };
            return [main, state];
          }),
        };
      },
      cancelSubscription: async (subscriptionContractId: string): Promise<void> => {
        await walletClient.cancelSubscription(
          new v0.CancelSubscriptionRequest().setIdleStateContractId(subscriptionContractId),
          getCreds()
        );
      },

      userStatus: async () => {
        const res = await walletClient.userStatus(new v0.UserStatusRequest(), getCreds());
        return { userOnboarded: res.getUserOnboarded(), partyId: res.getPartyId() };
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
