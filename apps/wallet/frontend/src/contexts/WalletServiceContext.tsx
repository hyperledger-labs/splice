import BigNumber from 'bignumber.js';
import {
  BaseApiMiddleware,
  Contract,
  OpenAPILoggingMiddleware,
  useUserState,
} from 'common-frontend';
import React, { useContext, useMemo } from 'react';
import {
  createConfiguration,
  ListTransactionsRequest,
  Middleware,
  RequestContext,
  ResponseContext,
  ServerConfiguration,
  WalletApi,
} from 'wallet-openapi';

import * as payment from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';
import {
  Subscription,
  SubscriptionContext,
  SubscriptionIdleState,
  SubscriptionPayment,
  SubscriptionRequest,
} from '@daml.js/wallet-payments/lib/CN/Wallet/Subscriptions';
import { AcceptedTransferOffer, TransferOffer } from '@daml.js/wallet/lib/CN/Wallet/TransferOffer';

import {
  BalanceChange,
  ListAcceptedTransferOffersResponse,
  ListResponse,
  ListSubscriptionRequestsResponse,
  ListSubscriptionsResponse,
  ListTransferOffersResponse,
  Transaction,
  Transfer,
  WalletBalance,
  SubscriptionRequestWithContext,
  AppPaymentRequest,
  Unknown,
  Notification,
} from '../models/models';

const WalletContext = React.createContext<WalletClient | undefined>(undefined);

export interface WalletProps {
  url: string;
}

export interface UserStatusResponse {
  userOnboarded: boolean;
  userWalletInstalled: boolean;
  partyId: string;
}

export interface WalletClient {
  tap: (amount: string) => Promise<void>;
  list: () => Promise<ListResponse>;
  getBalance: () => Promise<WalletBalance>;
  listTransactions: (beginAfterId?: string) => Promise<Transaction[]>;
  listTransferOffers: () => Promise<ListTransferOffersResponse>;
  createTransferOffer: (
    receiverPartyId: string,
    amount: BigNumber,
    description: string,
    expiresAt: Date,
    idempotencyKey: string
  ) => Promise<void>;
  acceptTransferOffer: (offerContractId: string) => Promise<void>;
  withdrawTransferOffer: (offerContractId: string) => Promise<void>;
  rejectTransferOffer: (offerContractId: string) => Promise<void>;
  listAcceptedTransferOffers: () => Promise<ListAcceptedTransferOffersResponse>;

  getAppPaymentRequest: (contractId: string) => Promise<AppPaymentRequest>;
  acceptAppPaymentRequest: (requestContractId: string) => Promise<void>;
  rejectAppPaymentRequest: (requestContractId: string) => Promise<void>;

  getSubscriptionRequest: (contractId: string) => Promise<SubscriptionRequestWithContext>;
  listSubscriptionRequests: () => Promise<ListSubscriptionRequestsResponse>;
  acceptSubscriptionRequest: (requestContractId: string) => Promise<void>;
  listSubscriptions: () => Promise<ListSubscriptionsResponse>;
  cancelSubscription: (subscriptionContractId: string) => Promise<void>;

  userStatus: () => Promise<UserStatusResponse>;
  selfGrantFeaturedAppRights: () => Promise<void>;
}

class ApiMiddleware
  extends BaseApiMiddleware<RequestContext, ResponseContext>
  implements Middleware {}

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
      promiseMiddleware: [
        new ApiMiddleware(userAccessToken),
        new OpenAPILoggingMiddleware('wallet'),
      ],
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
      getBalance: async (): Promise<WalletBalance> => {
        const balance = await walletClient.getBalance();
        return {
          availableCC: new BigNumber(balance.effectiveUnlockedQty),
        };
      },
      listTransactions: async (beginAfterId?: string): Promise<Transaction[]> => {
        const request: ListTransactionsRequest = { pageSize: 10, beginAfterId };
        const response = await walletClient.listTransactions(request);
        return response.items.flatMap<Transaction>(item => {
          const id = item.eventId;
          const receivers = (item.receivers || []).map(r => ({
            amount: new BigNumber(r.amount),
            party: r.party,
          }));
          const { date, transactionSubtype } = item;

          if (item.transactionType === 'balance_change') {
            const coinPrice = new BigNumber(item.coinPrice!);
            const balanceChange: BalanceChange = {
              transactionType: 'balance_change',
              transactionSubtype,
              id,
              date,
              receivers,
              coinPrice,
            };
            return [balanceChange];
          } else if (item.transactionType === 'transfer') {
            const coinPrice = new BigNumber(item.coinPrice!);
            const transfer: Transfer = {
              transactionType: 'transfer',
              transactionSubtype,
              id,
              date,
              receivers,
              // sender and provider MUST be available for transfer
              providerId: item.provider!,
              senderId: item.sender!.party,
              senderAmountCC: new BigNumber(item.sender!.amount),
              coinPrice,
            };
            return [transfer];
          } else if (item.transactionType === 'notification') {
            const notification: Notification = {
              transactionType: 'notification',
              transactionSubtype,
              id,
              date,
              details: item.details!,
            };
            return [notification];
          } else if (item.transactionType === 'unknown') {
            const unknown: Unknown = {
              transactionType: 'unknown',
              transactionSubtype,
              id,
              date,
            };
            return [unknown];
          } else {
            console.error('Unsupported transaction type.', item);
            return [];
          }
        });
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
          amount: amount.isInteger() ? amount.toFixed(1) : amount.toString(),
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
      getAppPaymentRequest: async contractId => {
        const response = await walletClient.getAppPaymentRequest(contractId);
        const appPaymentRequest = Contract.decodeOpenAPI(
          response.appPaymentRequest,
          payment.AppPaymentRequest
        );
        const deliveryOffer = Contract.decodeOpenAPI(response.deliveryOffer, payment.DeliveryOffer);
        return { appPaymentRequest, deliveryOffer };
      },
      acceptAppPaymentRequest: async requestContractId => {
        await walletClient.acceptAppPaymentRequest(requestContractId);
      },
      rejectAppPaymentRequest: async requestContractId => {
        await walletClient.rejectAppPaymentRequest(requestContractId);
      },
      getSubscriptionRequest: async contractId => {
        const response = await walletClient.getSubscriptionRequest(contractId);
        const subscriptionRequest = Contract.decodeOpenAPI(
          response.subscriptionRequest,
          SubscriptionRequest
        );
        const context = Contract.decodeOpenAPI(response.context, SubscriptionContext);
        return { subscriptionRequest, context };
      },
      listSubscriptionRequests: async (): Promise<ListSubscriptionRequestsResponse> => {
        const res = await walletClient.listSubscriptionRequests();
        return {
          subscriptionRequestsList: res.subscriptionRequests.map(sr => {
            const subscription = Contract.decodeOpenAPI(
              sr.subscriptionRequest,
              SubscriptionRequest
            );
            const context = Contract.decodeOpenAPI(sr.context, SubscriptionContext);
            return { subscriptionRequest: subscription, context };
          }),
        };
      },
      acceptSubscriptionRequest: async requestContractId => {
        await walletClient.acceptSubscriptionRequest(requestContractId);
      },
      listSubscriptions: async (): Promise<ListSubscriptionsResponse> => {
        const res = await walletClient.listSubscriptions();
        return {
          subscriptionsList: res.subscriptions.map(sub => {
            const subscription = Contract.decodeOpenAPI(sub.subscription, Subscription);
            const state = sub.state.payment
              ? {
                  type: 'payment' as 'payment',
                  value: Contract.decodeOpenAPI(sub.state.payment!, SubscriptionPayment),
                }
              : {
                  type: 'idle' as 'idle',
                  value: Contract.decodeOpenAPI(sub.state.idle!, SubscriptionIdleState),
                };
            const context = Contract.decodeOpenAPI(sub.context, SubscriptionContext);
            return { subscription, state, context };
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
