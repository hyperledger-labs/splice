import { SubscriptionButton } from 'common-frontend/lib/components/WalletButtons';
import { useState } from 'react';

import { FormGroup, Typography } from '@mui/material';

import { DirectoryInstall } from '@daml.js/directory/lib/CN/Directory';

import Searchbar from '../components/Searchbar';
import { useLedgerApiClient } from '../contexts/LedgerApiContext';
import { config } from '../utils';

const RequestDirectoryEntry: React.FC<{ primaryParty: string; provider: string }> = ({
  primaryParty,
  provider,
}) => {
  const [entryName, setEntryName] = useState<string>('');
  const ledgerApiClient = useLedgerApiClient();

  const requestEntry = async () => {
    const directoryInstall = await ledgerApiClient.queryDirectoryInstall(primaryParty, provider);
    if (!directoryInstall) {
      throw new Error('Failed to find DirectoryInstall');
    }
    const res = await ledgerApiClient.exercise(
      [primaryParty],
      [],
      DirectoryInstall.DirectoryInstall_RequestEntry,
      directoryInstall.contractId,
      { name: entryName }
    );

    console.debug('Created SubscriptionRequest');
    return res._2;
  };

  return (
    <div>
      <Typography variant="body1">Register your name in the Canton Network</Typography>
      <Typography variant="h1">Search for the name you’d like to register</Typography>
      <FormGroup row>
        <Searchbar
          value={entryName}
          onChange={event => setEntryName(event.target.value)}
          id="entry-name-field"
        />
        <SubscriptionButton
          variant="pill"
          id="request-entry-with-sub-button"
          text="Search"
          createPaymentRequest={requestEntry}
          walletPath={config.services.wallet.uiUrl}
        />
      </FormGroup>
    </div>
  );
};

export default RequestDirectoryEntry;
