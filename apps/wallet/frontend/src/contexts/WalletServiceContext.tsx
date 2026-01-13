// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as external from '@lfdecentralizedtrust/wallet-external-openapi';
import { useUserState } from '@lfdecentralizedtrust/splice-common-frontend';
import {
  BaseApiMiddleware,
  Contract,
  ContractWithState,
  OpenAPILoggingMiddleware,
} from '@lfdecentralizedtrust/splice-common-frontend-utils';
import BigNumber from 'bignumber.js';
import React, { useContext, useMemo } from 'react';
import {
  AllocateAmuletRequest,
  createConfiguration,
  ListTransactionsRequest,
  Middleware,
  RequestContext,
  ResponseContext,
  ServerConfiguration,
  WalletApi,
  WalletFeatureSupportResponse,
} from '@lfdecentralizedtrust/wallet-openapi';

import * as payment from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';
import { AmuletTransferInstruction } from '@daml.js/splice-amulet/lib/Splice/AmuletTransferInstruction';
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
  MintingDelegation,
  MintingDelegationProposal,
} from '@daml.js/splice-wallet/lib/Splice/Wallet/MintingDelegation/module';

export interface MintingDelegationWithStatus {
  contract: Contract<MintingDelegation>;
  beneficiaryOnboarded: boolean;
}

export interface MintingDelegationProposalWithStatus {
  contract: Contract<MintingDelegationProposal>;
  beneficiaryOnboarded: boolean;
}

export interface MintingDelegationProposalWithOnboardedStatus {
  contract: Contract<MintingDelegationProposal>;
  beneficiaryOnboarded: boolean;
}

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
  ListTokenStandardTransfersResponse,
} from '../models/models';
import { AllocationRequest } from '@daml.js/splice-api-token-allocation-request/lib/Splice/Api/Token/AllocationRequestV1/module';
import { AmuletAllocation } from '@daml.js/splice-amulet/lib/Splice/AmuletAllocation';
import { ContractId } from '@daml/types';

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
  listTokenStandardTransfers: () => Promise<ListTokenStandardTransfersResponse>;
  createTransferOffer: (
    receiverPartyId: string,
    amount: BigNumber,
    description: string,
    expiresAt: Date,
    trackingId: string
  ) => Promise<void>;
  createTransferPreapproval: () => Promise<void>;
  createTransferViaTokenStandard: (
    receiverPartyId: string,
    amount: BigNumber,
    description: string,
    expiresAt: Date,
    trackingId: string
  ) => Promise<void>;
  transferPreapprovalSend: (
    receiverPartyId: string,
    amount: BigNumber,
    deduplicationId: string,
    description: string
  ) => Promise<void>;
  acceptTransferOffer: (offerContractId: string) => Promise<void>;
  acceptTokenStandardTransfer: (transferContractId: string) => Promise<void>;
  rejectTransferOffer: (offerContractId: string) => Promise<void>;
  rejectTokenStandardTransfer: (transferContractId: string) => Promise<void>;
  listAcceptedTransferOffers: () => Promise<ListAcceptedTransferOffersResponse>;

  listAmuletAllocations: () => Promise<Contract<AmuletAllocation>[]>;
  listAllocationRequests: () => Promise<Contract<AllocationRequest>[]>;
  listMintingDelegations: () => Promise<MintingDelegationWithStatus[]>;
  listMintingDelegationProposals: () => Promise<MintingDelegationProposalWithStatus[]>;
  acceptMintingDelegationProposal: (
    proposalContractId: ContractId<MintingDelegationProposal>
  ) => Promise<void>;
  rejectMintingDelegationProposal: (
    proposalContractId: ContractId<MintingDelegationProposal>
  ) => Promise<void>;
  withdrawMintingDelegation: (delegationContractId: ContractId<MintingDelegation>) => Promise<void>;
  rejectAllocationRequest: (allocationRequestCid: ContractId<AllocationRequest>) => Promise<void>;
  createAllocation: (allocateAmuletRequest: AllocateAmuletRequest) => Promise<void>;
  withdrawAllocation: (allocationCid: ContractId<AmuletAllocation>) => Promise<void>;

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

  featureSupport: () => Promise<WalletFeatureSupportResponse>;
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
              transferInstructionCid: item.transfer_instruction_cid,
            };
            return [balanceChange];
          } else if (item.transaction_type === 'transfer') {
            const amuletPrice = new BigNumber(item.amulet_price!);
            const appRewardsUsed = new BigNumber(item.app_rewards_used);
            const validatorRewardsUsed = new BigNumber(item.validator_rewards_used);
            const svRewardsUsed = new BigNumber(item.sv_rewards_used);
            const transferInstructionAmount = item.transfer_instruction_amount
              ? new BigNumber(item.transfer_instruction_amount)
              : undefined;
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
              description: item.description,
              transferInstructionCid: item.transfer_instruction_cid,
              transferInstructionAmount: transferInstructionAmount,
              transferInstructionReceiver: item.transfer_instruction_receiver,
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
      createTransferViaTokenStandard: async (
        receiverPartyId,
        amount,
        description,
        expiresAt,
        trackingId
      ) => {
        const request = {
          receiver_party_id: receiverPartyId,
          amount: amount.isInteger() ? amount.toFixed(1) : amount.toString(),
          description: description,
          expires_at: expiresAt.getTime() * 1000,
          tracking_id: trackingId,
        };
        await walletClient.createTokenStandardTransfer(request);
      },
      createTransferPreapproval: async () => {
        await walletClient.createTransferPreapproval();
      },
      transferPreapprovalSend: async (
        receiverPartyId: string,
        amount: BigNumber,
        deduplicationId: string,
        description: string
      ) => {
        const request = {
          receiver_party_id: receiverPartyId,
          amount: amount.isInteger() ? amount.toFixed(1) : amount.toString(),
          deduplication_id: deduplicationId,
          description: description === '' ? undefined : description,
        };
        await walletClient.transferPreapprovalSend(request);
      },
      listTransferOffers: async (): Promise<ListTransferOffersResponse> => {
        const res = await externalWalletClient.listTransferOffers();
        return {
          offersList: res.offers.map(c => Contract.decodeOpenAPI(c, TransferOffer)),
        };
      },
      listTokenStandardTransfers: async (): Promise<ListTokenStandardTransfersResponse> => {
        const res = await walletClient.listTokenStandardTransfers();
        const transfers = res.transfers.map(c =>
          Contract.decodeOpenAPI(c, AmuletTransferInstruction)
        );
        return { transfers };
      },
      acceptTransferOffer: async offerContractId => {
        await walletClient.acceptTransferOffer(offerContractId);
      },
      acceptTokenStandardTransfer: async transferContractId => {
        await walletClient.acceptTokenStandardTransfer(transferContractId);
      },
      rejectTransferOffer: async offerContractId => {
        await walletClient.rejectTransferOffer(offerContractId);
      },
      rejectTokenStandardTransfer: async transferContractId => {
        await walletClient.rejectTokenStandardTransfer(transferContractId);
      },
      listAcceptedTransferOffers: async (): Promise<ListAcceptedTransferOffersResponse> => {
        const res = await walletClient.listAcceptedTransferOffers();
        return {
          acceptedOffersList: res.accepted_offers.map(c =>
            Contract.decodeOpenAPI(c, AcceptedTransferOffer)
          ),
        };
      },
      listAmuletAllocations: async () => {
        const res = await walletClient.listAmuletAllocations();
        return res.allocations.map(all => Contract.decodeOpenAPI(all.contract, AmuletAllocation));
      },
      listAllocationRequests: async () => {
        const res = await walletClient.listAllocationRequests();
        return res.allocation_requests.map(ar =>
          Contract.decodeOpenAPI(ar.contract, AllocationRequest)
        );
      },
      listMintingDelegations: async () => {
        const res = await walletClient.listMintingDelegations();
        return res.delegations.map(d => ({
          contract: Contract.decodeOpenAPI(d.contract, MintingDelegation),
          beneficiaryOnboarded: d.beneficiary_onboarded,
        }));
      },
      listMintingDelegationProposals: async () => {
        const res = await walletClient.listMintingDelegationProposals();
        return res.proposals.map(p => ({
          contract: Contract.decodeOpenAPI(p.contract, MintingDelegationProposal),
          beneficiaryOnboarded: p.beneficiary_onboarded,
        }));
      },
      acceptMintingDelegationProposal: async proposalContractId => {
        await walletClient.acceptMintingDelegationProposal(proposalContractId);
      },
      rejectMintingDelegationProposal: async proposalContractId => {
        await walletClient.rejectMintingDelegationProposal(proposalContractId);
      },
      withdrawMintingDelegation: async delegationContractId => {
        await walletClient.rejectMintingDelegation(delegationContractId);
      },
      rejectAllocationRequest: async allocationRequestCid => {
        await walletClient.rejectAllocationRequest(allocationRequestCid);
      },
      createAllocation: async allocateAmuletRequest => {
        await walletClient.allocateAmulet(allocateAmuletRequest);
      },
      withdrawAllocation: async allocationCid => {
        await walletClient.withdrawAmuletAllocation(allocationCid);
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
                  type: 'payment' as const,
                  value: Contract.decodeOpenAPI(sub.state.payment!, SubscriptionPayment),
                }
              : {
                  type: 'idle' as const,
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
      featureSupport: async (): Promise<WalletFeatureSupportResponse> => {
        return await walletClient.featureSupport();
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
