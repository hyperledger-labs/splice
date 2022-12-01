import { SubscriptionButton } from 'common-frontend/lib/components/WalletButtons';
import { useState } from 'react';

import { FormGroup, TextField, Typography } from '@mui/material';

import { DirectoryInstall } from '@daml.js/directory/lib/CN/Directory';

import { useDirectoryLedgerApiClient } from '../contexts/DirectoryLedgerApiContext';
import { config } from '../utils';

const RequestDirectoryEntry: React.FC<{ primaryParty: string; provider: string }> = ({
  primaryParty,
  provider,
}) => {
  const [entryName, setEntryName] = useState<string>('');
  const ledgerApiClient = useDirectoryLedgerApiClient();

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
