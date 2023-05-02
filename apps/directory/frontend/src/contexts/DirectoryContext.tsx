import { Contract, sameContracts, useInterval } from 'common-frontend';
import {
  DirectoryClient,
  useDirectoryClient,
} from 'common-frontend/lib/contexts/DirectoryServiceContext';
import React, { useCallback, useContext, useEffect, useMemo, useState } from 'react';

import {
  DirectoryInstall,
  DirectoryEntry,
  DirectoryInstallRequest,
  DirectoryEntryContext,
} from '@daml.js/directory/lib/CN/Directory';
import {
  Subscription,
  SubscriptionContext,
  SubscriptionIdleState,
  SubscriptionPayData,
  SubscriptionPayment,
  SubscriptionRequest,
} from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';
import { ContractId } from '@daml/types';

import { LedgerApiClient, useLedgerApiClient } from './LedgerApiContext';

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
  const directoryClient = useDirectoryClient();

  // Get parties
  const primaryPartyId = usePrimaryParty(ledgerApiClient);
  const providerPartyId = useProviderParty(directoryClient);

  // Set up app install contract
  const [directoryInstallContract, setDirectoryInstallContract] = useState<
    Contract<DirectoryInstall> | undefined
  >();

  useEffect(() => {
    const setupInstallContract = async () => {
      if (primaryPartyId && providerPartyId) {
        console.debug('Searching for DirectoryInstall');
        const install = await ledgerApiClient.queryDirectoryInstall(
          primaryPartyId,
          providerPartyId
        );
        if (install) {
          console.debug('DirectoryInstall found');
          setDirectoryInstallContract(install);
        } else {
          console.debug('DirectoryInstall not found, creating DirectoryInstallRequest');
          await ledgerApiClient.create([primaryPartyId], DirectoryInstallRequest, {
            user: primaryPartyId,
            provider: providerPartyId,
          });
          console.debug('Created DirectoryInstallRequest, waiting for DirectoryInstall');

          const install = await retry(100, 1000, async () => {
            return ledgerApiClient
              .queryDirectoryInstall(primaryPartyId, providerPartyId)
              .then(res => {
                if (res === undefined) {
                  throw new Error('No Install contract found');
                } else {
                  return res;
                }
              });
          });

          setDirectoryInstallContract(install);
        }
      }
    };
    setupInstallContract().catch(err => console.error('Failed to setup install contract: ', err));
  }, [primaryPartyId, providerPartyId, ledgerApiClient]);

  // Fetch user's directory entries
  const [directoryEntries, setDirectoryEntries] = useState<Contract<DirectoryEntry>[]>([]);
  const [subscriptionContracts, setSubscriptionContracts] = useState<Contract<Subscription>[]>([]);
  const [directoryEntriesContexts, setDirectoryEntriesContexts] = useState<
    Contract<DirectoryEntryContext>[]
  >([]);
  const [subWithEntryContextCid, setSubWithEntryContextCid] = useState<
    SubscriptionWithEntryContextCid[]
  >([]);
  const [subscriptionIdleStates, setSubscriptionIdleStates] = useState<
    Contract<SubscriptionIdleState>[]
  >([]);
  const [subscriptionPaymentResults, setSubscriptionPaymentResults] = useState<
    Contract<SubscriptionPayment>[]
  >([]);
  const [subPayData, setSubPayData] = useState<SubPayData[]>([]);

  const fetchDirectoryEntries = useCallback(async () => {
    if (primaryPartyId && providerPartyId) {
      const current = await ledgerApiClient.queryOwnedDirectoryEntries(
        primaryPartyId,
        providerPartyId
      );
      setDirectoryEntries(prev => (sameContracts(prev, current) ? prev : current));
    }
  }, [ledgerApiClient, primaryPartyId, providerPartyId]);
  useInterval(fetchDirectoryEntries);

  const entriesWithContextCid = useMemo<EntryWithContextContractId[]>(() => {
    return directoryEntries.map(entry => {
      const context = directoryEntriesContexts.find(ec => ec.payload.name === entry.payload.name);
      return {
        entry,
        contextContractId: context ? context.contractId : undefined,
      };
    });
  }, [directoryEntries, directoryEntriesContexts]);

  const fetchDirectoryEntriesContexts = useCallback(async () => {
    if (primaryPartyId && providerPartyId) {
      const current = await ledgerApiClient.queryEntryContexts(primaryPartyId, providerPartyId);
      setDirectoryEntriesContexts(prev => (sameContracts(prev, current) ? prev : current));
    }
  }, [ledgerApiClient, primaryPartyId, providerPartyId]);
  useInterval(fetchDirectoryEntriesContexts);

  const fetchSubscriptions = useCallback(async () => {
    if (primaryPartyId && providerPartyId) {
      const current = await ledgerApiClient.querySubscriptions(primaryPartyId, providerPartyId);
      setSubscriptionContracts(prev => (sameContracts(prev, current) ? prev : current));
    }
  }, [ledgerApiClient, primaryPartyId, providerPartyId]);
  useInterval(fetchSubscriptions);

  useMemo(() => {
    const cids = subscriptionContracts
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
    setSubWithEntryContextCid(cids);
  }, [entriesWithContextCid, subscriptionContracts]);

  const fetchSubscriptionIdleState = useCallback(async () => {
    if (primaryPartyId && providerPartyId) {
      const current = await ledgerApiClient.querySubscriptionIdleState(
        primaryPartyId,
        providerPartyId
      );
      setSubscriptionIdleStates(prev => (sameContracts(prev, current) ? prev : current));
    }
  }, [ledgerApiClient, primaryPartyId, providerPartyId]);
  useInterval(fetchSubscriptionIdleState);

  const fetchSubscriptionPaymentResults = useCallback(async () => {
    if (primaryPartyId && providerPartyId) {
      const current = await ledgerApiClient.querySubscriptionPayment(
        primaryPartyId,
        providerPartyId
      );
      setSubscriptionPaymentResults(prev => (sameContracts(prev, current) ? prev : current));
    }
  }, [ledgerApiClient, primaryPartyId, providerPartyId]);
  useInterval(fetchSubscriptionPaymentResults);

  useMemo(() => {
    const idlePayData = subscriptionIdleStates.map(idle => {
      return {
        payData: idle.payload.payData,
        contractId: idle.payload.subscription,
      };
    });

    const payResultsData = subscriptionPaymentResults.map(payment => {
      return {
        payData: payment.payload.payData,
        contractId: payment.payload.subscription,
      };
    });
    const merged: SubPayData[] = idlePayData.concat(payResultsData);
    setSubPayData(merged);
  }, [subscriptionIdleStates, subscriptionPaymentResults]);

  const requestEntry = async (entryName: string) => {
    if (!primaryPartyId) {
      throw new Error('No primary party found while requesting entry');
    }
    if (!providerPartyId) {
      throw new Error('No provider party found while requesting entry');
    }

    const directoryInstall = await ledgerApiClient.queryDirectoryInstall(
      primaryPartyId,
      providerPartyId
    );
    if (!directoryInstall) {
      throw new Error('Failed to find DirectoryInstall');
    }
    const res = await ledgerApiClient.exercise(
      [primaryPartyId],
      [],
      DirectoryInstall.DirectoryInstall_RequestEntry,
      directoryInstall.contractId,
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

function useProviderParty(directoryClient: DirectoryClient): string | undefined {
  const [providerPartyId, setProviderPartyId] = useState<string | undefined>();

  useEffect(() => {
    const fetchProviderParty = async () => {
      try {
        const response = await directoryClient.getProviderPartyId();
        setProviderPartyId(response.providerPartyId);
      } catch (err) {
        console.error('Error finding provider party', err);
        throw new Error('Error finding provider party, please confirm user onboarded.');
      }
    };
    fetchProviderParty();
  }, [directoryClient]);

  return providerPartyId;
}

function usePrimaryParty(ledgerApiClient: LedgerApiClient): string | undefined {
  const [primaryParty, setPrimaryParty] = useState<string>();

  useEffect(() => {
    const fetchPrimaryParty = async () => {
      try {
        setPrimaryParty(await retry(100, 1000, () => ledgerApiClient.getPrimaryParty()));
      } catch (err) {
        console.error('Error finding primary party for user', err);
        console.error(JSON.stringify(err));
        throw new Error(
          'Error finding primary party for user, please confirm user onboarded to this participant.'
        );
      }
    };
    fetchPrimaryParty();
  }, [ledgerApiClient]);

  return primaryParty;
}

// TODO(#3550) Factor out retry functionality.
const sleep = async (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

const retry = async <T,>(
  maxRetries: number,
  retryDelay: number,
  task: () => Promise<T>
): Promise<T> => {
  let retries = 0;
  let error;
  while (retries < maxRetries) {
    if (retries > 0) {
      console.debug('Retrying now...');
    }
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const response: { type: 'success'; value: T } | { type: 'retryable'; error: any } = await task()
      .then<{ type: 'success'; value: T }>(r => {
        return { type: 'success', value: r };
      })
      .catch(e => ({ type: 'retryable', error: e }));
    switch (response.type) {
      case 'retryable':
        console.debug(
          `Found retryable error ${JSON.stringify(error)}, retrying after ${retryDelay}ms...`
        );
        error = response.error;
        retries++;
        await sleep(retryDelay);
        break;
      case 'success':
        return response.value;
    }
  }
  console.debug('Exceeded retries, giving up...');
  throw error;
};
