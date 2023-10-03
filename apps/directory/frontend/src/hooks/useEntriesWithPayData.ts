import { Contract, PollingStrategy } from 'common-frontend';
import { useMemo } from 'react';

import { DirectoryEntry, DirectoryEntryContext } from '@daml.js/directory/lib/CN/Directory';
import {
  Subscription,
  SubscriptionContext,
  SubscriptionIdleState,
  SubscriptionPayData,
  SubscriptionPayment,
} from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';
import { ContractId } from '@daml/types';

import {
  useSubscriptionPayments,
  useSubscriptionIdleStates,
  useSubscriptions,
  useDirectoryEntries,
  useDirectoryEntryContexts,
} from '.';

interface EntryWithPayData {
  contractId: string;
  expiresAt: string;
  entryName: string;
  amount: string;
  currency: string;
  paymentInterval: string;
  paymentDuration: string;
}

interface EntryWithSubscriptionContext {
  entry: Contract<DirectoryEntry>;
  subscriptionCtxCid: ContractId<SubscriptionContext>;
}

type SubscriptionPayDataMap = Map<ContractId<Subscription>, SubscriptionPayData>;

/**
 * Payment data regarding the user's subscription can be stored in
 * two different templates, depending on the current state of the subscription flow:
 *  SubscriptionPayment or SubscriptionIdleState.
 *
 * This hook queries both templates and constructs a Map between the underlying
 * subscription contract IDs and their associated payment data
 */
const useSubscriptionsPayData = (refetchInterval: false | number): SubscriptionPayDataMap => {
  const { data: subscriptionIdleStates = [] } = useSubscriptionIdleStates(refetchInterval);
  const { data: subscriptionPayments = [] } = useSubscriptionPayments(refetchInterval);

  function toPayDataAndCid(
    contract: Contract<SubscriptionIdleState> | Contract<SubscriptionPayment>
  ) {
    return {
      payData: contract.payload.payData,
      subscriptionCid: contract.payload.subscription,
    };
  }

  const subscriptionPayData: [ContractId<Subscription>, SubscriptionPayData][] = useMemo(
    () =>
      subscriptionIdleStates
        .map(toPayDataAndCid)
        .concat(subscriptionPayments.map(toPayDataAndCid))
        .reduce(
          (acc, { subscriptionCid, payData }) => [...acc, [subscriptionCid, payData]],
          [] as [ContractId<Subscription>, SubscriptionPayData][]
        ),
    [subscriptionIdleStates, subscriptionPayments]
  );

  return new Map<ContractId<Subscription>, SubscriptionPayData>(subscriptionPayData);
};

/**
 * To associate a DirectoryEntry contract to its subscription, we need to look at
 * DirectoryEntryContext contracts which implement the SubscriptionContext interface
 *
 * This hook joins the former to the latter, while also casting the
 * DirectoryEntryContext type to the SubscriptionContext type
 */
const useEntriesWithSubscriptionContext = (
  refetchInterval: false | number
): EntryWithSubscriptionContext[] => {
  const { data: directoryEntries = [] } = useDirectoryEntries(refetchInterval);
  const { data: directoryEntriesContexts = [] } = useDirectoryEntryContexts(refetchInterval);

  return directoryEntries.reduce((acc, entry) => {
    const context = directoryEntriesContexts.find(dec => dec.payload.name === entry.payload.name);
    if (context === undefined) {
      // in case there's a race in which the DirectoryEntry shows up before its DirectoryEntryContext,
      // do nothing. Once the DEC arrives, this hook will re-render and add it to the result.
      return acc;
    } else {
      const subscriptionCtxCid = DirectoryEntryContext.toInterface(
        SubscriptionContext,
        context.contractId
      );

      return [...acc, { entry, subscriptionCtxCid }];
    }
  }, [] as EntryWithSubscriptionContext[]);
};

/**
 * Perform a `join`-like operation across the list of entries we have, with the payment
 * data associated with the underlying subscription
 */
const useEntriesWithPayData = (): EntryWithPayData[] => {
  const refetchInterval = PollingStrategy.FIXED;

  const entriesWithSubscriptionContext = useEntriesWithSubscriptionContext(refetchInterval);
  const subscriptionsPayData = useSubscriptionsPayData(refetchInterval);
  const { data: subscriptions = [] } = useSubscriptions(refetchInterval);

  return entriesWithSubscriptionContext.reduce((acc, { entry, subscriptionCtxCid }) => {
    const subscriptionCid = subscriptions.find(
      s => s.payload.context === subscriptionCtxCid
    )?.contractId;

    const paymentData = subscriptionCid ? subscriptionsPayData.get(subscriptionCid) : undefined;

    return [
      ...acc,
      {
        contractId: entry.contractId,
        expiresAt: entry.payload.expiresAt,
        entryName: entry.payload.name,
        amount: paymentData ? paymentData.paymentAmount.amount : '...',
        currency: paymentData ? paymentData.paymentAmount.currency : '...',
        paymentInterval: paymentData ? paymentData.paymentInterval.microseconds : '...',
        paymentDuration: paymentData ? paymentData.paymentDuration.microseconds : '...',
      },
    ];
  }, [] as EntryWithPayData[]);
};

export default useEntriesWithPayData;
