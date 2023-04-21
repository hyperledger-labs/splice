import BigNumber from 'bignumber.js';
import { Contract } from 'common-frontend';
import {
  AcceptedTransferOffer,
  TransferOffer,
} from 'common-frontend/daml.js/wallet-0.1.0/lib/CN/Wallet/TransferOffer/module';
import { CoinPosition } from 'wallet-openapi';

import * as payment from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';
import {
  Subscription,
  SubscriptionContext,
  SubscriptionIdleState,
  SubscriptionPayment,
  SubscriptionRequest,
} from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';
import { Party, ContractId } from '@daml/types';

import { ConvertedCurrency } from '../utils/currencyConversion';

export interface WalletBalance {
  availableCC: BigNumber;
}

// TODO(#3981): Improve the classification of transactions
export type Transaction = Transfer | BalanceChange | Automation;
export interface Transfer {
  id: string;
  transactionType: 'transfer';
  receivers: TransactionReceiver[]; // will be empty for e.g. mergers & self-transfers
  senderId: Party;
  providerId: Party;
  senderAmountCC: BigNumber; // this includes all amounts of receivers + fees
  date: Date;
  coinPrice: BigNumber;
}
export interface BalanceChange {
  id: string;
  transactionType: 'balance_change';
  receivers: TransactionReceiver[];
  date: Date;
  coinPrice: BigNumber;
}
export interface Automation {
  // receivers is effectively the current user
  id: string;
  transactionType: 'automation';
  providerId: Party;
  senderAmountCC: BigNumber;
  date: Date;
  coinPrice: BigNumber;
}

export interface TransactionReceiver {
  amount: BigNumber;
  party: Party;
}

export interface WalletTransferOffer {
  contractId: ContractId<TransferOffer>;
  ccAmount: string;
  usdAmount: string;
  conversionRate: string;
  convertedCurrency: ConvertedCurrency;
  senderId: string;
  expiry: string;
}

export interface WalletSubscription {
  subscription: Contract<Subscription>;
  context: Contract<SubscriptionContext>;
  state: SubscriptionState;
}

export type SubscriptionState =
  | { type: 'idle'; value: Contract<SubscriptionIdleState> }
  | { type: 'payment'; value: Contract<SubscriptionPayment> };

export interface SubscriptionRequestWithContext {
  subscriptionRequest: Contract<SubscriptionRequest>;
  context: Contract<SubscriptionContext>;
}

export interface AppPaymentRequest {
  appPaymentRequest: Contract<payment.AppPaymentRequest>;
  deliveryOffer: Contract<payment.DeliveryOffer>;
}

//=== Endpoint responses ===
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

export interface ListSubscriptionRequestsResponse {
  subscriptionRequestsList: SubscriptionRequestWithContext[];
}

export interface ListSubscriptionsResponse {
  subscriptionsList: WalletSubscription[];
}
