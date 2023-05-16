import { Contract } from 'common-frontend';
import React, { useContext, useEffect, useMemo } from 'react';

import {
  DirectoryEntry,
  DirectoryEntryContext,
  DirectoryInstall,
  DirectoryInstallRequest,
} from '@daml.js/directory/lib/CN/Directory';
import {
  Subscription,
  SubscriptionContext,
  SubscriptionPayData,
  SubscriptionRequest,
} from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';
import { ContractId } from '@daml/types';

import {
  useDirectoryInstall,
  useSubscriptionIdleStates,
  useOwnedDirectoryEntries,
  useSubscriptions,
  useDirectoryEntryContexts,
  usePrimaryParty,
  useProviderParty,
} from '../hooks';
import useSubscriptionPayments from '../hooks/useSubscriptionPayments';
import { useLedgerApiClient } from './LedgerApiContext';

type DirectoryUiState = {
  primaryPartyId?: string;
  providerPartyId?: string;
  directoryInstallContract?: Contract<DirectoryInstall>;
  directoryEntries: Contract<DirectoryEntry>[];
  requestEntry: (entryName: string) => Promise<ContractId<SubscriptionRequest>>;
  directoryEntriesContexts: Contract<DirectoryEntryContext>[];
  subscriptionPayData: SubPayData[];
  subscriptionWithEntryContextCid: SubscriptionWithEntryContextCid[];
  entryWithContextCid: EntryWithContextContractId[];
};

export interface SubPayData {
  payData: SubscriptionPayData;
  contractId: ContractId<Subscription>;
}

export interface EntryWithPayData {
  contractId: string;
  expiresAt: string;
  entryName: string;
  amount: string;
  currency: string;
  paymentInterval: string;
  paymentDuration: string;
}

interface EntryWithContextContractId {
  entry: Contract<DirectoryEntry>;
  contextContractId: ContractId<DirectoryEntryContext> | undefined;
}

interface SubscriptionWithEntryContextCid {
  subContractId: ContractId<Subscription>;
  entryContextContractId: ContractId<SubscriptionContext>;
}

const DirectoryUiContext = React.createContext<DirectoryUiState | undefined>(undefined);

export const DirectoryUiStateProvider: React.FC<React.PropsWithChildren> = ({ children }) => {
  // Establish API clients
  const ledgerApiClient = useLedgerApiClient();

  // Get parties
  const { data: primaryPartyId } = usePrimaryParty();
  const { data: providerPartyId } = useProviderParty();

  // Set up app install contract
  const { data: directoryInstallContract } = useDirectoryInstall(primaryPartyId, providerPartyId);

  useEffect(() => {
    const setupInstallContract = async () => {
      if (primaryPartyId && providerPartyId && ledgerApiClient) {
        console.debug('Setting up DirectoryInstall');
        if (directoryInstallContract) {
          console.debug('DirectoryInstall found');
        } else {
          console.debug('DirectoryInstall not found, creating DirectoryInstallRequest');
          await ledgerApiClient.create([primaryPartyId], DirectoryInstallRequest, {
            user: primaryPartyId,
            provider: providerPartyId,
          });
          console.debug('Created DirectoryInstallRequest, waiting for DirectoryInstall');
        }
      }
    };
    setupInstallContract().catch(err => console.error('Failed to setup install contract: ', err));
  }, [directoryInstallContract, providerPartyId, primaryPartyId, ledgerApiClient]);

  // Fetch user's directory entries
  const { data: directoryEntries = [] } = useOwnedDirectoryEntries(primaryPartyId, providerPartyId);
  const { data: subscriptions = [] } = useSubscriptions(primaryPartyId, providerPartyId);
  const { data: directoryEntriesContexts = [] } = useDirectoryEntryContexts(
    primaryPartyId,
    providerPartyId
  );
  const { data: subscriptionIdleStates = [] } = useSubscriptionIdleStates(
    primaryPartyId,
    providerPartyId
  );
  const { data: subscriptionPayments = [] } = useSubscriptionPayments(
    primaryPartyId,
    providerPartyId
  );

  const entriesWithContextCid = useMemo<EntryWithContextContractId[]>(() => {
    return directoryEntries.map(entry => {
      const context = directoryEntriesContexts.find(ec => ec.payload.name === entry.payload.name);
      return {
        entry,
        contextContractId: context ? context.contractId : undefined,
      };
    });
  }, [directoryEntries, directoryEntriesContexts]);

  const subWithEntryContextCid = useMemo(() => {
    return subscriptions
      .filter(sub => {
        return entriesWithContextCid.some(
          r =>
            r.contextContractId !== undefined &&
            DirectoryEntryContext.toInterface(SubscriptionContext, r.contextContractId) ===
              sub.payload.context
        );
      })
      .map(s => {
        return {
          subContractId: s.contractId,
          entryContextContractId: s.payload.context,
        };
      });
  }, [entriesWithContextCid, subscriptions]);

  const subPayData: SubPayData[] = useMemo(() => {
    const idlePayData = subscriptionIdleStates.map(idle => {
      return {
        payData: idle.payload.payData,
        contractId: idle.payload.subscription,
      };
    });

    const payResultsData = subscriptionPayments.map(payment => {
      return {
        payData: payment.payload.payData,
        contractId: payment.payload.subscription,
      };
    });
    return idlePayData.concat(payResultsData);
  }, [subscriptionIdleStates, subscriptionPayments]);

  const requestEntry = async (entryName: string) => {
    if (!ledgerApiClient) {
      throw new Error('No ledgerAPIClient available while requesting entry');
    }
    if (!primaryPartyId) {
      throw new Error('No primary party found while requesting entry');
    }
    if (!providerPartyId) {
      throw new Error('No provider party found while requesting entry');
    }

    if (!directoryInstallContract) {
      throw new Error('Failed to find DirectoryInstall');
    }

    const res = await ledgerApiClient.exercise(
      [primaryPartyId],
      [],
      DirectoryInstall.DirectoryInstall_RequestEntry,
      directoryInstallContract.contractId,
      { name: entryName }
    );

    console.debug('Created SubscriptionRequest');
    return res._2;
  };

  return (
    <DirectoryUiContext.Provider
      value={{
        primaryPartyId,
        providerPartyId,
        directoryInstallContract,
        directoryEntries,
        requestEntry,
        directoryEntriesContexts,
        subscriptionPayData: subPayData,
        subscriptionWithEntryContextCid: subWithEntryContextCid,
        entryWithContextCid: entriesWithContextCid,
      }}
    >
      {children}
    </DirectoryUiContext.Provider>
  );
};

export const useDirectoryUiState: () => DirectoryUiState = () => {
  const client = useContext<DirectoryUiState | undefined>(DirectoryUiContext);
  if (!client) {
    throw new Error('Directory state not initialized');
  }
  return client;
};
