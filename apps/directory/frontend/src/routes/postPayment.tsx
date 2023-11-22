import { Loading } from 'common-frontend';
import React from 'react';
import { Link, useSearchParams } from 'react-router-dom';

import { CloseRounded, DoneRounded } from '@mui/icons-material';
import { Button, Stack, Typography } from '@mui/material';

import { useLookupEntryByName } from '../hooks';
import { usePrimaryParty } from '../hooks/queries/usePrimaryParty';
import { ENTRY_NAME_SUFFIX, toFullEntryName } from '../utils';

export const PostPayment: React.FC = () => {
  const [searchParams] = useSearchParams();
  const entryName = searchParams.get('entryName') || '';

  const primaryPartyId = usePrimaryParty();
  const {
    data: directoryEntry,
    isLoading: directoryEntryIsLoading,
    isError: directoryEntryIsError,
    error: directoryEntryError,
  } = useLookupEntryByName(entryName, ENTRY_NAME_SUFFIX, true);

  if (!entryName) {
    console.error('PostPayment rendered without entryName.');
    return <></>;
  }

  const fullEntryName = toFullEntryName(entryName, ENTRY_NAME_SUFFIX);

  const directoryEntryOwner = directoryEntry?.entryContract?.payload.user;

  if (!primaryPartyId || !directoryEntryOwner || directoryEntryIsLoading) {
    return <DirectoryLoading fullEntryName={fullEntryName} />;
  }

  if (directoryEntryIsError) {
    return (
      <DirectoryFailed
        errorMessage={`${fullEntryName} was not registered. Something went wrong.`}
        errorDetails={
          directoryEntryError instanceof Error ? directoryEntryError.message : undefined
        }
      />
    );
  }

  if (primaryPartyId !== directoryEntryOwner) {
    return (
      <DirectoryFailed
        errorMessage={`${fullEntryName} was not registered. It was claimed by someone else.`}
      />
    );
  }

  return <DirectoryReady fullEntryName={fullEntryName} />;
};

interface DirectoryProps {
  fullEntryName: string;
}

const DirectoryLoading: React.FC<DirectoryProps> = ({ fullEntryName }) => {
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
        <Typography variant="h5">{fullEntryName}</Typography>
      </Stack>
      <UnverifiedInfo fullEntryName={fullEntryName} />
    </Stack>
  );
};

const DirectoryFailed: React.FC<{ errorMessage: string; errorDetails?: string }> = ({
  errorMessage,
  errorDetails,
}) => {
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
      <Stack spacing={5}>
        <Typography variant="h5">
          {errorMessage}
          <br />
          Please try again, or search for a different name.
        </Typography>
        {errorDetails && <Typography variant="body2">{errorDetails}</Typography>}
      </Stack>
      <YourDirectoryEntriesButton />
    </Stack>
  );
};

const DirectoryReady: React.FC<DirectoryProps> = ({ fullEntryName }) => {
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
        <Typography variant="h5">
          {fullEntryName} is now registered in the Canton Network.
        </Typography>
        <YourDirectoryEntriesButton />
      </Stack>
      <UnverifiedInfo fullEntryName={fullEntryName} />
    </Stack>
  );
};

const UnverifiedInfo: React.FC<DirectoryProps> = ({ fullEntryName }) => {
  const isUnverified = fullEntryName.endsWith('.unverified.cns');
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
