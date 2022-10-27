import { useState } from 'react';

import { Button, FormGroup, TextField, Typography } from '@mui/material';

import { DirectoryInstall } from '@daml.js/directory/lib/CN/Directory';

import { useDirectoryLedgerApiClient } from '../contexts/DirectoryLedgerApiContext';

const RequestDirectoryEntry: React.FC<{ primaryParty: string; provider: string }> = ({
  primaryParty,
  provider,
}) => {
  const [entryName, setEntryName] = useState<string>('');
  const ledgerApiClient = useDirectoryLedgerApiClient();

  const onRequestEntry = async () => {
    await ledgerApiClient.exerciseByKey(
      [primaryParty],
      [],
      DirectoryInstall.DirectoryInstall_RequestEntry,
      { _1: provider, _2: primaryParty },
      { name: entryName }
    );
    console.debug('Created DirectoryEntryRequest');
  };
  const onRequestEntryWithSubscription = async () => {
    await ledgerApiClient.exerciseByKey(
      [primaryParty],
      [],
      DirectoryInstall.DirectoryInstall_RequestEntryWithSubscription,
      { _1: provider, _2: primaryParty },
      { name: entryName }
    );
    console.debug('Created SubscriptionRequest');
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
        <Button variant="contained" onClick={() => onRequestEntry()} id="request-entry-button">
          Request
        </Button>
        <Button
          variant="contained"
          onClick={() => onRequestEntryWithSubscription()}
          id="request-entry-with-sub-button"
        >
          Request with subscription
        </Button>
      </FormGroup>
    </div>
  );
};

export default RequestDirectoryEntry;
