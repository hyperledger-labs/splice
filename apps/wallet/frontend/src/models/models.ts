// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import BigNumber from 'bignumber.js';
import { AmuletPosition } from '@lfdecentralizedtrust/wallet-openapi';

import { AmuletTransferInstruction } from '@daml.js/splice-amulet/lib/Splice/AmuletTransferInstruction';
import {
  Subscription,
  SubscriptionIdleState,
  SubscriptionPayment,
  SubscriptionRequest,
} from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Subscriptions';
import {
  AcceptedTransferOffer,
  TransferOffer,
} from '@daml.js/splice-wallet/lib/Splice/Wallet/TransferOffer/module';
import { Party, ContractId } from '@daml/types';

import { ConvertedCurrency } from '../utils/currencyConversion';

export interface WalletBalance {
  availableCC: BigNumber;
}

export interface TransactionSubtype {
  template_id: string;
  choice: string;
  amulet_operation?: string;
}

export type Transaction = Transfer | BalanceChange | Notification | Unknown;

export interface Transfer {
  id: string;
  transactionType: 'transfer';
  transactionSubtype: TransactionSubtype;
  receivers: TransactionReceiver[]; // will be empty for e.g. mergers & self-transfers
  senderId: Party;
  senderAmountCC: BigNumber; // this includes all amounts of receivers + fees
  date: Date;
  amuletPrice: BigNumber;
  appRewardsUsed: BigNumber;
  validatorRewardsUsed: BigNumber;
  svRewardsUsed: BigNumber;
  description?: string;
  transferInstructionCid?: string;
  transferInstructionReceiver?: string;
  transferInstructionAmount?: BigNumber;
}

export interface BalanceChange {
  id: string;
  transactionType: 'balance_change';
  transactionSubtype: TransactionSubtype;
  receivers: TransactionReceiver[];
  date: Date;
  amuletPrice: BigNumber;
  transferInstructionCid?: string;
}

export interface Notification {
  id: string;
  transactionType: 'notification';
  transactionSubtype: TransactionSubtype;
  date: Date;
  details: string;
}

export interface Unknown {
  id: string;
  transactionType: 'unknown';
  transactionSubtype: TransactionSubtype;
  date: Date;
}

export interface TransactionReceiver {
  amount: BigNumber;
  party: Party;
}

export interface WalletTransferOffer {
  contractId: ContractId<TransferOffer> | ContractId<AmuletTransferInstruction>;
  ccAmount: string;
  usdAmount: string;
  conversionRate: string;
  convertedCurrency: ConvertedCurrency;
  senderId: string;
  expiry: string;
  isTokenStandard: boolean;
}

export interface WalletSubscription {
  subscription: Contract<Subscription>;
  state: SubscriptionState;
}

export type SubscriptionState =
  | { type: 'idle'; value: Contract<SubscriptionIdleState> }
  | { type: 'payment'; value: Contract<SubscriptionPayment> };

//=== Endpoint responses ===
export interface GetBalanceResponse {
  round: number;
  effectiveUnlockedQty: string;
  effectiveLockedQty: string;
  totalHoldingFees: string;
}

export interface ListResponse {
  lockedAmulets: AmuletPosition[];
  amulets: AmuletPosition[];
}

export interface ListTransferOffersResponse {
  offersList: Contract<TransferOffer>[];
}

export interface ListTokenStandardTransfersResponse {
  transfers: Contract<AmuletTransferInstruction>[];
}

export interface ListAcceptedTransferOffersResponse {
  acceptedOffersList: Contract<AcceptedTransferOffer>[];
}

export interface ListSubscriptionRequestsResponse {
  subscriptionRequestsList: Contract<SubscriptionRequest>[];
}

export interface ListSubscriptionsResponse {
  subscriptionsList: WalletSubscription[];
}
