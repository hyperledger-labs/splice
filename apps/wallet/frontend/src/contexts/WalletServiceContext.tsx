// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as external from 'wallet-external-openapi';
import BigNumber from 'bignumber.js';
import { useUserState } from 'common-frontend';
import {
  BaseApiMiddleware,
  Contract,
  ContractWithState,
  OpenAPILoggingMiddleware,
} from 'common-frontend-utils';
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

import * as payment from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';
import { AppPaymentRequest } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';
import {
  Subscription,
  SubscriptionIdleState,
  SubscriptionPayment,
  SubscriptionRequest,
} from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Subscriptions';
import {
  AcceptedTransferOffer,
  TransferOffer,
} from '@daml.js/splice-wallet/lib/Splice/Wallet/TransferOffer';

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
    trackingId: string
  ) => Promise<void>;
  createTransferPreapproval: () => Promise<void>;
  transferPreapprovalSend: (
    receiverPartyId: string,
    amount: BigNumber,
    deduplicationId: string
  ) => Promise<void>;
  acceptTransferOffer: (offerContractId: string) => Promise<void>;
  withdrawTransferOffer: (offerContractId: string) => Promise<void>;
  rejectTransferOffer: (offerContractId: string) => Promise<void>;
  listAcceptedTransferOffers: () => Promise<ListAcceptedTransferOffersResponse>;

  getAppPaymentRequest: (contractId: string) => Promise<ContractWithState<AppPaymentRequest>>;
  acceptAppPaymentRequest: (requestContractId: string) => Promise<void>;
  rejectAppPaymentRequest: (requestContractId: string) => Promise<void>;

  getSubscriptionRequest: (contractId: string) => Promise<Contract<SubscriptionRequest>>;
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

class ExternalApiMiddleware
  extends BaseApiMiddleware<external.RequestContext, external.ResponseContext>
  implements external.Middleware {}

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

    const externalConfiguration = external.createConfiguration({
      baseServer: new external.ServerConfiguration(url, {}),
      promiseMiddleware: [
        new ExternalApiMiddleware(userAccessToken),
        new OpenAPILoggingMiddleware('wallet'),
      ],
    });

    const walletClient = new WalletApi(configuration);
    const externalWalletClient = new external.WalletApi(externalConfiguration);

    return {
      list: async (): Promise<ListResponse> => {
        const res = await walletClient.list();
        return { amulets: res.amulets, lockedAmulets: res.locked_amulets };
      },
      tap: async amount => {
        const request = { amount: amount };
        await walletClient.tap(request);
      },
      getBalance: async (): Promise<WalletBalance> => {
        const balance = await walletClient.getBalance();
        return {
          availableCC: new BigNumber(balance.effective_unlocked_qty),
        };
      },
      listTransactions: async (begin_after_id?: string): Promise<Transaction[]> => {
        const request: ListTransactionsRequest = { page_size: 10, begin_after_id };
        const response = await walletClient.listTransactions(request);
        return response.items.flatMap<Transaction>(item => {
          const id = item.event_id;
          const receivers = (item.receivers || []).map(r => ({
            amount: new BigNumber(r.amount),
            party: r.party,
          }));
          const { date, transaction_subtype } = item;

          if (item.transaction_type === 'balance_change') {
            const amuletPrice = new BigNumber(item.amulet_price!);
            const balanceChange: BalanceChange = {
              transactionType: 'balance_change',
              transactionSubtype: transaction_subtype,
              id,
              date,
              receivers,
              amuletPrice,
            };
            return [balanceChange];
          } else if (item.transaction_type === 'transfer') {
            const amuletPrice = new BigNumber(item.amulet_price!);
            const appRewardsUsed = new BigNumber(item.app_rewards_used);
            const validatorRewardsUsed = new BigNumber(item.validator_rewards_used);
            const svRewardsUsed = new BigNumber(item.sv_rewards_used);
            const transfer: Transfer = {
              transactionType: 'transfer',
              transactionSubtype: transaction_subtype,
              id,
              date,
              receivers,
              // sender and provider MUST be available for transfer
              providerId: item.provider!,
              senderId: item.sender!.party,
              senderAmountCC: new BigNumber(item.sender!.amount),
              amuletPrice,
              appRewardsUsed,
              validatorRewardsUsed,
              svRewardsUsed,
            };
            return [transfer];
          } else if (item.transaction_type === 'notification') {
            const notification: Notification = {
              transactionType: 'notification',
              transactionSubtype: transaction_subtype,
              id,
              date,
              details: item.details!,
            };
            return [notification];
          } else if (item.transaction_type === 'unknown') {
            const unknown: Unknown = {
              transactionType: 'unknown',
              transactionSubtype: transaction_subtype,
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
      createTransferOffer: async (receiverPartyId, amount, description, expiresAt, trackingId) => {
        const request = {
          receiver_party_id: receiverPartyId,
          amount: amount.isInteger() ? amount.toFixed(1) : amount.toString(),
          description: description,
          expires_at: expiresAt.getTime() * 1000,
          tracking_id: trackingId,
        };
        await externalWalletClient.createTransferOffer(request);
      },
      createTransferPreapproval: async () => {
        await walletClient.createTransferPreapproval();
      },
      transferPreapprovalSend: async (
        receiverPartyId: string,
        amount: BigNumber,
        deduplicationId: string
      ) => {
        const request = {
          receiver_party_id: receiverPartyId,
          amount: amount.isInteger() ? amount.toFixed(1) : amount.toString(),
          deduplication_id: deduplicationId,
        };
        await walletClient.transferPreapprovalSend(request);
      },
      listTransferOffers: async (): Promise<ListTransferOffersResponse> => {
        const res = await externalWalletClient.listTransferOffers();
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
          acceptedOffersList: res.accepted_offers.map(c =>
            Contract.decodeOpenAPI(c, AcceptedTransferOffer)
          ),
        };
      },
      getAppPaymentRequest: async contractId => {
        const response = await walletClient.getAppPaymentRequest(contractId);
        const contract = Contract.decodeOpenAPI(response.contract, payment.AppPaymentRequest);
        return { contract, domainId: response.domain_id };
      },
      acceptAppPaymentRequest: async requestContractId => {
        await walletClient.acceptAppPaymentRequest(requestContractId);
      },
      rejectAppPaymentRequest: async requestContractId => {
        await walletClient.rejectAppPaymentRequest(requestContractId);
      },
      getSubscriptionRequest: async contractId => {
        const response = await walletClient.getSubscriptionRequest(contractId);
        return Contract.decodeOpenAPI(response, SubscriptionRequest);
      },
      listSubscriptionRequests: async (): Promise<ListSubscriptionRequestsResponse> => {
        const res = await walletClient.listSubscriptionRequests();
        return {
          subscriptionRequestsList: res.subscription_requests.map(sr =>
            Contract.decodeOpenAPI(sr, SubscriptionRequest)
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
            return { subscription, state };
          }),
        };
      },
      cancelSubscription: async (subscriptionContractId: string): Promise<void> => {
        await walletClient.cancelSubscriptionRequest(subscriptionContractId);
      },

      userStatus: async () => {
        const res = await walletClient.userStatus();
        return {
          userOnboarded: res.user_onboarded,
          userWalletInstalled: res.user_wallet_installed,
          partyId: res.party_id,
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
