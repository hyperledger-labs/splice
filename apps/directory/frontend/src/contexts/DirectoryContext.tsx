import { Contract, sameContracts, useInterval } from 'common-frontend';
import {
  DirectoryClient,
  useDirectoryClient,
} from 'common-frontend/lib/contexts/DirectoryServiceContext';
import React, { useCallback, useContext, useEffect, useState } from 'react';

import {
  DirectoryInstall,
  DirectoryEntry,
  DirectoryInstallRequest,
} from '@daml.js/directory/lib/CN/Directory';
import { SubscriptionRequest } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';
import { ContractId } from '@daml/types';

import { LedgerApiClient, useLedgerApiClient } from './LedgerApiContext';

type DirectoryUiState = {
  primaryPartyId?: string;
  providerPartyId?: string;
  directoryInstallContract?: Contract<DirectoryInstall>;
  directoryEntries: Contract<DirectoryEntry>[];
  requestEntry: (entryName: string) => Promise<ContractId<SubscriptionRequest>>;
};

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
