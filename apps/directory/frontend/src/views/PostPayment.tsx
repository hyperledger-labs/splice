import { Loading, useDirectoryClient } from 'common-frontend';
import React, { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';

import { CloseRounded, DoneRounded } from '@mui/icons-material';
import { Button, Stack, Typography } from '@mui/material';

import { useDirectoryUiState } from '../contexts/DirectoryContext';

type DirectoryStatus =
  | { type: 'loading' }
  | { type: 'ready' }
  | { type: 'error'; error: DirectoryStatusError };
type DirectoryStatusError =
  | { type: 'assigned_to_other' }
  | { type: 'unknown'; errorMessage: string };
export const PostPayment: React.FC = () => {
  const [searchParams] = useSearchParams();
  const entryName = searchParams.get('entryName');

  const { lookupEntryByName } = useDirectoryClient();
  const { primaryPartyId } = useDirectoryUiState();
  const [directoryStatus, setDirectoryStatus] = useState<DirectoryStatus>({ type: 'loading' });
  useEffect(() => {
    let effectCancelled = false;
    const tryLookup: (retriesDone: number) => void = retriesLeft => {
      if (entryName && directoryStatus.type === 'loading') {
        lookupEntryByName(entryName).then(
          response => {
            if (effectCancelled) {
              return;
            }
            if (response.payload.user === primaryPartyId) {
              setDirectoryStatus({ type: 'ready' });
            } else {
              setDirectoryStatus({ type: 'error', error: { type: 'assigned_to_other' } });
            }
          },
          err => {
            if (effectCancelled) {
              return;
            }
            if (retriesLeft === 0) {
              setDirectoryStatus({
                type: 'error',
                error: { type: 'unknown', errorMessage: JSON.stringify(err) },
              });
            } else {
              setTimeout(() => tryLookup(retriesLeft - 1), 1000);
            }
          }
        );
      }
    };

    const maxRetries = 10;
    tryLookup(maxRetries);
    return () => {
      effectCancelled = true;
    };
  }, [entryName, lookupEntryByName, directoryStatus, primaryPartyId]);

  if (!entryName) {
    console.error('PostPayment rendered without entryName.');
    return <></>;
  }

  switch (directoryStatus.type) {
    case 'loading':
      return <DirectoryLoading entryName={entryName} />;
    case 'error':
      return <DirectoryFailed entryName={entryName} error={directoryStatus.error} />;
    case 'ready':
      return <DirectoryReady entryName={entryName} />;
  }
};

interface DirectoryProps {
  entryName: string;
}

const DirectoryLoading: React.FC<DirectoryProps> = ({ entryName }) => {
  return (
    <Stack
      spacing={20}
      alignItems="center"
      textAlign="center"
      justifyContent="center"
      marginTop={10}
    >
      <Stack spacing={3}>
        <Loading />
        <Typography variant="h5">Completing Registration</Typography>
        <Typography variant="h5">{entryName}</Typography>
      </Stack>
      <UnverifiedInfo entryName={entryName} />
    </Stack>
  );
};

const DirectoryFailed: React.FC<DirectoryProps & { error: DirectoryStatusError }> = ({
  entryName,
  error,
}) => {
  let errorComponent;
  switch (error.type) {
    case 'assigned_to_other':
      errorComponent = (
        <Typography variant="h5">
          {entryName} was not registered. It was claimed by someone else.
          <br />
          Please try again, or search for a different name.
        </Typography>
      );
      break;
    case 'unknown':
      errorComponent = (
        <Stack spacing={5}>
          <Typography variant="h5">
            {entryName} was not registered. Something went wrong.
            <br />
            Please try again, or search for a different name.
          </Typography>
          <Typography variant="body2">{error.errorMessage}</Typography>
        </Stack>
      );
      break;
  }

  return (
    <Stack
      alignItems="center"
      textAlign="center"
      justifyContent="center"
      marginTop={10}
      spacing={3}
    >
      <CloseRounded color="error" style={statusIconStyle} />
      <Typography variant="h5">Payment Failed</Typography>
      {errorComponent}
      <YourDirectoryEntriesButton />
    </Stack>
  );
};

const DirectoryReady: React.FC<DirectoryProps> = ({ entryName }) => {
  return (
    <Stack
      spacing={20}
      alignItems="center"
      textAlign="center"
      justifyContent="center"
      marginTop={10}
    >
      <Stack spacing={3} alignItems="center" textAlign="center" justifyContent="center">
        <DoneRounded color="success" style={statusIconStyle} />
        <Typography variant="h5">Thank you for confirming your subscription.</Typography>
        <Typography variant="h5">{entryName} is now registered in the Canton Network.</Typography>
        <YourDirectoryEntriesButton />
      </Stack>
      <UnverifiedInfo entryName={entryName} />
    </Stack>
  );
};

const UnverifiedInfo: React.FC<DirectoryProps> = ({ entryName }) => {
  const isUnverified = entryName.endsWith('.unverified.cns');
  if (isUnverified) {
    return (
      <Stack>
        <Typography variant="body1" color="colors.neutral.80">
          Why <i>unverified</i>.cns?
        </Typography>
        <Typography variant="body1" color="colors.neutral.80">
          There is no verification of a user’s identity for CNS entry names at this point so entries
          are required to include ".unverified" in the name to allow for the later addition of
          verified entries.
        </Typography>
      </Stack>
    );
  } else {
    return null;
  }
};

const YourDirectoryEntriesButton: React.FC = () => {
  return (
    <Link to="/">
      <Button variant="pill" id="directory-entries-button">
        Go to your Directory Entries
      </Button>
    </Link>
  );
};

const statusIconStyle = { width: '80px', height: '80px', border: '3px solid', borderRadius: '50%' };

export default PostPayment;
