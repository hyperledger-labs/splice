import { Contract } from 'common-frontend';
import { SubscriptionButton, TransferButton } from 'common-frontend/lib/components/WalletButtons';
import { useState } from 'react';

import { FormGroup, TextField, Typography } from '@mui/material';

import { DirectoryEntryOffer, DirectoryInstall } from '@daml.js/directory/lib/CN/Directory';

import { useDirectoryLedgerApiClient } from '../contexts/DirectoryLedgerApiContext';
import { config } from '../utils';

const RequestDirectoryEntry: React.FC<{ primaryParty: string; provider: string }> = ({
  primaryParty,
  provider,
}) => {
  const [entryName, setEntryName] = useState<string>('');
  const ledgerApiClient = useDirectoryLedgerApiClient();

  const waitForDirectoryEntryOffer: (
    entryName: string,
    retries: number
  ) => Promise<Contract<DirectoryEntryOffer>> = async (entryName: string, retries: number) => {
    if (retries < 0) {
      throw new Error('Timeout waiting for directory to create an entry offer');
    }
    const offer = await ledgerApiClient.queryDirectoryEntryOffer(primaryParty, provider, entryName);
    if (offer) {
      return offer;
    }
    await new Promise(resolve => setTimeout(resolve, 500));
    return waitForDirectoryEntryOffer(entryName, retries - 1);
  };

  const requestEntry = async () => {
    const directoryInstall = await ledgerApiClient.queryDirectoryInstall(primaryParty, provider);
    if (!directoryInstall) {
      throw new Error('Failed to find DirectoryInstall');
    }
    await ledgerApiClient.exercise(
      [primaryParty],
      [],
      DirectoryInstall.DirectoryInstall_RequestEntry,
      directoryInstall.contractId,
      { name: entryName }
    );
    const offer = await waitForDirectoryEntryOffer(entryName, 4);
    const ret = await ledgerApiClient.queryDirectoryEntryPaymentRequest(
      primaryParty,
      offer.contractId
    );
    if (!ret) {
      throw new Error(
        'Directory entry offer created, but could not find a corresponding payment request'
      );
    }
    console.debug('Created DirectoryEntryRequest');
    return ret.contractId;
  };

  const requestEntryWithSubscription = async () => {
    const directoryInstall = await ledgerApiClient.queryDirectoryInstall(primaryParty, provider);
    if (!directoryInstall) {
      throw new Error('Failed to find DirectoryInstall');
    }
    const res = await ledgerApiClient.exercise(
      [primaryParty],
      [],
      DirectoryInstall.DirectoryInstall_RequestEntryWithSubscription,
      directoryInstall.contractId,
      { name: entryName }
    );
    console.debug('Created SubscriptionRequest');
    return res._2;
  };

  return (
    <div>
      <Typography variant="h6">Request New Directory Entry</Typography>
      <FormGroup row>
        <TextField
          label="Name"
          value={entryName}
          onChange={event => setEntryName(event.target.value)}
          id="entry-name-field"
        ></TextField>
        <TransferButton
          variant="contained"
          id="request-entry-button"
          text="Request"
          createPaymentRequest={requestEntry}
          walletPath={config.wallet.uiUrl}
        />
        <SubscriptionButton
          variant="contained"
          id="request-entry-with-sub-button"
          text="Request with subscription"
          createPaymentRequest={requestEntryWithSubscription}
          walletPath={config.wallet.uiUrl}
        />
      </FormGroup>
    </div>
  );
};

export default RequestDirectoryEntry;
