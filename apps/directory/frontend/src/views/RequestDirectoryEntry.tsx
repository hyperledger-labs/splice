import { SubscriptionButton } from 'common-frontend/lib/components/WalletButtons';
import React, { useState } from 'react';

import { Stack, Typography } from '@mui/material';

import Searchbar from '../components/Searchbar';
import { useDirectoryUiState } from '../contexts/DirectoryContext';
import { config } from '../utils';

const RequestDirectoryEntry: React.FC = () => {
  const [entryName, setEntryName] = useState<string>('');
  const { requestEntry } = useDirectoryUiState();

  return (
    <Stack justifyContent="center" mt={2} spacing={2}>
      <Typography variant="body1">Register your name in the Canton Network</Typography>
      <Typography variant="h3">Search for the name you’d like to register</Typography>
      <Stack direction="row" spacing={2}>
        <Searchbar
          sx={{ flexGrow: '1' }}
          value={entryName}
          onChange={event => setEntryName(event.target.value)}
          id="entry-name-field"
        />
        <SubscriptionButton
          variant="pill"
          id="request-entry-with-sub-button"
          text="Search"
          createPaymentRequest={() => requestEntry(entryName)}
          redirectPath={`/post-payment?entryName=${entryName}`}
          walletPath={config.services.wallet.uiUrl}
        />
      </Stack>
    </Stack>
  );
};

export default RequestDirectoryEntry;
