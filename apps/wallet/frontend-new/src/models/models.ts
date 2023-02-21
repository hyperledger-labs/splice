export interface WalletBalance {
  totalCC: string;
  totalUSD: string;
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

export interface TransferOffer {
  totalCCAmount: string;
  totalUSDAmount: string;
  conversionRate: string;
  senderId: string;
  providerId: string;
  expiry: string;
}

export interface Subscription {
  provider: { description: string; cns: string }; // Receiver is currently missing in the design
  price: { amount: string; currency: string; perPeriod: string };
  nextPaymentDue: string;
}
