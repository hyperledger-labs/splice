import { SubscriptionButton } from 'common-frontend/lib/components/WalletButtons';
import { useState } from 'react';

import { FormGroup, Typography } from '@mui/material';

import Searchbar from '../components/Searchbar';
import { useDirectoryUiState } from '../contexts/DirectoryContext';
import { config } from '../utils';

const RequestDirectoryEntry: React.FC = () => {
  const [entryName, setEntryName] = useState<string>('');
  const { requestEntry } = useDirectoryUiState();

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
          createPaymentRequest={() => requestEntry(entryName)}
          walletPath={config.services.wallet.uiUrl}
        />
      </FormGroup>
    </div>
  );
};

export default RequestDirectoryEntry;
