import { Contract } from 'common-frontend';
import {
  AcceptedTransferOffer,
  TransferOffer,
} from 'common-frontend/daml.js/wallet-0.1.0/lib/CN/Wallet/TransferOffer/module';
import { Decimal } from 'decimal.js';
import { CoinPosition } from 'wallet-openapi';

import { AppPaymentRequest } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';
import {
  Subscription,
  SubscriptionIdleState,
  SubscriptionPayment,
  SubscriptionRequest,
} from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';

export interface WalletBalance {
  availableCC: Decimal;
}

export interface Transaction {
  action: string;
  recipientId: string;
  providerId: string;
  totalCCAmount: string;
  totalUSDAmount: string;
  conversionRate: string;
  date: string;
}

export interface WalletTransferOffer {
  totalCCAmount: string;
  totalUSDAmount: string;
  conversionRate: string;
  senderId: string;
  providerId: string;
  expiry: string;
}

export interface WalletSubscription {
  provider: { description: string; cns: string }; // Receiver is currently missing in the design
  price: { amount: string; currency: string; perPeriod: string };
  nextPaymentDue: string;
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
