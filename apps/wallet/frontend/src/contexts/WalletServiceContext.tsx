import * as v0 from 'common-protobuf/com/daml/network/wallet/v0/wallet_service_pb';
import { Contract } from 'common-frontend';
import { WalletServicePromiseClient } from 'common-protobuf/com/daml/network/wallet/v0/wallet_service_grpc_web_pb';
import { Metadata } from 'grpc-web';
import React, { useContext, useMemo } from 'react';

import { AppPaymentRequest } from '@daml.js/wallet-payments/lib/CN/Wallet/Payment';
import {
  SubscriptionRequest,
  Subscription,
  SubscriptionIdleState,
  SubscriptionPayment,
} from '@daml.js/wallet-payments/lib/CN/Wallet/Subscriptions';
import { PaymentChannelProposal } from '@daml.js/wallet/lib/CN/Wallet/PaymentChannel';

import { useUserState } from './UserContext';

const WalletContext = React.createContext<WalletClient | undefined>(undefined);

export interface WalletProps {
  url: string;
}

export interface ListResponse {
  lockedCoins: v0.CoinPosition[];
  coins: v0.CoinPosition[];
}

export interface ListPaymentChannelRequestsResponse {
  proposalsList: Contract<PaymentChannelProposal>[];
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

export interface UserStatusResponse {
  userOnboarded: boolean;
  partyId: string;
}

export interface WalletClient {
  tap: (quantity: string) => Promise<void>;
  list: () => Promise<ListResponse>;
  executeDirectTransfer: (quantity: string, receiverPartyId: string) => Promise<void>;

  listPaymentChannelProposals: () => Promise<ListPaymentChannelRequestsResponse>;
  proposePaymentChannel: (
    receiverPartyId: string,
    senderTransferFeeRatio: string,
    allowDirectTransfers: boolean,
    allowOffers: boolean,
    allowRequests: boolean
  ) => Promise<void>;
  acceptPaymentChannelProposal: (proposalContractId: string) => Promise<void>;

  listAppPaymentRequests: () => Promise<ListAppPaymentRequestsResponse>;
  acceptAppPaymentRequests: (requestContractId: string) => Promise<void>;

  listSubscriptionRequests: () => Promise<ListSubscriptionRequestsResponse>;
  acceptSubscriptionRequest: (requestContractId: string) => Promise<void>;
  listSubscriptions: () => Promise<ListSubscriptionsResponse>;

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
      tap: async quantity => {
        await walletClient.tap(new v0.TapRequest().setQuantity(quantity), getCreds());
      },
      executeDirectTransfer: async (quantity, receiverPartyId) => {
        await walletClient.executeDirectTransfer(
          new v0.ExecuteDirectTransferRequest()
            .setQuantity(quantity)
            .setReceiverPartyId(receiverPartyId),
          getCreds()
        );
      },

      proposePaymentChannel: async (
        receiverPartyId,
        senderTransferFeeRatio,
        allowDirectTransfers,
        allowOffers,
        allowRequests
      ) => {
        await walletClient.proposePaymentChannel(
          new v0.ProposePaymentChannelRequest()
            .setReceiverPartyId(receiverPartyId)
            .setSenderTransferFeeRatio(senderTransferFeeRatio)
            .setAllowDirectTransfers(allowDirectTransfers)
            .setAllowOffers(allowOffers)
            .setAllowRequests(allowRequests),
          getCreds()
        );
      },
      listPaymentChannelProposals: async (): Promise<ListPaymentChannelRequestsResponse> => {
        const res = await walletClient.listPaymentChannelProposals(
          new v0.ListPaymentChannelProposalsRequest(),
          getCreds()
        );

        return {
          proposalsList: res
            .getProposalsList()
            .map(c => Contract.decode(c, PaymentChannelProposal)),
        };
      },
      acceptPaymentChannelProposal: async proposalContractId => {
        await walletClient.acceptPaymentChannelProposal(
          new v0.AcceptPaymentChannelProposalRequest().setProposalContractId(proposalContractId),
          getCreds()
        );
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
      acceptAppPaymentRequests: async requestContractId => {
        await walletClient.acceptAppPaymentRequest(
          new v0.AcceptAppPaymentRequestRequest().setRequestContractId(requestContractId),
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
