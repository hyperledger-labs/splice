import * as v0 from 'common-protobuf/com/daml/network/wallet/v0/wallet_service_pb';
import { Contract } from 'common-frontend';
import { WalletServicePromiseClient } from 'common-protobuf/com/daml/network/wallet/v0/wallet_service_grpc_web_pb';
import { Metadata } from 'grpc-web';
import React, { useContext, useState, useEffect, useMemo } from 'react';

import {
  AppMultiPaymentRequest,
  AppPaymentRequest,
  PaymentChannelProposal,
} from '@daml.js/wallet/lib/CN/Wallet';

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

export interface ListAppMultiPaymentRequestsResponse {
  paymentRequestsList: Contract<AppMultiPaymentRequest>[];
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
  acceptAppPaymentRequest: (requestContractId: string) => Promise<void>;

  listAppMultiPaymentRequests: () => Promise<ListAppMultiPaymentRequestsResponse>;
  acceptAppMultiPaymentRequests: (requestContractId: string) => Promise<void>;

  userStatus: () => Promise<UserStatusResponse>;
}

interface Credentials extends Metadata {
  Authorization: string;
}

export const WalletClientProvider: React.FC<React.PropsWithChildren<WalletProps>> = ({
  url,
  children,
}) => {
  const [creds, setCreds] = useState<Credentials>();
  const { userId, userAccessToken } = useUserState();

  useEffect(() => {
    if (userAccessToken) {
      setCreds({ Authorization: userAccessToken });
    }
  }, [userAccessToken]);

  // Collection of methods that wrap the raw grpc codegen client methods because everything in that API is constructor-chain based, requires auth headers, and just generally awkward to use
  const friendlyClient: WalletClient | undefined = useMemo(() => {
    if (!userId) return undefined;

    const walletClient = new WalletServicePromiseClient(url, null, null);

    // TODO(i1012) -- remove wallet user context when auth is required
    const wctx = new v0.WalletContext().setUserName(userId);

    return {
      list: async (): Promise<ListResponse> => {
        const res = await walletClient.list(new v0.ListRequest().setWalletCtx(wctx), creds);
        return { coins: res.getCoinsList(), lockedCoins: res.getLockedCoinsList() };
      },
      tap: async quantity => {
        await walletClient.tap(new v0.TapRequest().setQuantity(quantity).setWalletCtx(wctx), creds);
      },
      executeDirectTransfer: async (quantity, receiverPartyId) => {
        await walletClient.executeDirectTransfer(
          new v0.ExecuteDirectTransferRequest()
            .setQuantity(quantity)
            .setReceiverPartyId(receiverPartyId)
            .setWalletCtx(wctx),
          creds
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
            .setAllowRequests(allowRequests)
            .setWalletCtx(wctx),
          creds
        );
      },
      listPaymentChannelProposals: async (): Promise<ListPaymentChannelRequestsResponse> => {
        const res = await walletClient.listPaymentChannelProposals(
          new v0.ListPaymentChannelProposalsRequest().setWalletCtx(wctx),
          creds
        );

        return {
          proposalsList: res
            .getProposalsList()
            .map(c => Contract.decode(c, PaymentChannelProposal)),
        };
      },
      acceptPaymentChannelProposal: async proposalContractId => {
        await walletClient.acceptPaymentChannelProposal(
          new v0.AcceptPaymentChannelProposalRequest()
            .setProposalContractId(proposalContractId)
            .setWalletCtx(wctx),
          creds
        );
      },

      listAppPaymentRequests: async (): Promise<ListAppPaymentRequestsResponse> => {
        const res = await walletClient.listAppPaymentRequests(
          new v0.ListAppPaymentRequestsRequest().setWalletCtx(wctx),
          creds
        );
        return {
          paymentRequestsList: res
            .getPaymentRequestsList()
            .map(c => Contract.decode(c, AppPaymentRequest)),
        };
      },
      acceptAppPaymentRequest: async requestContractId => {
        await walletClient.acceptAppPaymentRequest(
          new v0.AcceptAppPaymentRequestRequest()
            .setRequestContractId(requestContractId)
            .setWalletCtx(wctx),
          creds
        );
      },

      listAppMultiPaymentRequests: async (): Promise<ListAppMultiPaymentRequestsResponse> => {
        const res = await walletClient.listAppMultiPaymentRequests(
          new v0.ListAppMultiPaymentRequestsRequest().setWalletCtx(wctx),
          creds
        );
        return {
          paymentRequestsList: res
            .getPaymentRequestsList()
            .map(c => Contract.decode(c, AppMultiPaymentRequest)),
        };
      },
      acceptAppMultiPaymentRequests: async requestContractId => {
        await walletClient.acceptAppMultiPaymentRequest(
          new v0.AcceptAppMultiPaymentRequestRequest()
            .setRequestContractId(requestContractId)
            .setWalletCtx(wctx),
          creds
        );
      },

      userStatus: async () => {
        const res = await walletClient.userStatus(
          new v0.UserStatusRequest().setWalletCtx(wctx),
          creds
        );
        return { userOnboarded: res.getUserOnboarded(), partyId: res.getPartyId() };
      },
    };
  }, [url, creds, userId]);

  return <WalletContext.Provider value={friendlyClient}>{children}</WalletContext.Provider>;
};

export const useWalletClient: () => WalletClient = () => {
  const client = useContext<WalletClient | undefined>(WalletContext);
  if (!client) {
    throw new Error('Wallet client not initialized');
  }
  return client;
};
