import { Loading } from 'common-frontend';
import React from 'react';
import { Link, useSearchParams } from 'react-router-dom';

import { CloseRounded, DoneRounded } from '@mui/icons-material';
import { Button, Stack, Typography } from '@mui/material';

import { useLookupEntryByName, usePrimaryParty } from '../hooks';

export const PostPayment: React.FC = () => {
  const [searchParams] = useSearchParams();
  const entryName = searchParams.get('entryName') || undefined;

  const {
    data: primaryPartyId,
    isLoading: primaryPartyIdIsLoading,
    isError: primaryPartyIdIsError,
    error: primaryPartyIdError,
  } = usePrimaryParty();
  const {
    data: directoryEntry,
    isLoading: directoryEntryIsLoading,
    isError: directoryEntryIsError,
    error: directoryEntryError,
  } = useLookupEntryByName(entryName);

  if (!entryName) {
    console.error('PostPayment rendered without entryName.');
    return <></>;
  }

  if (primaryPartyIdIsLoading || directoryEntryIsLoading) {
    return <DirectoryLoading entryName={entryName} />;
  }

  if (primaryPartyIdIsError) {
    return (
      <DirectoryFailed
        errorMessage={`Could not retrieve primary party.`}
        errorDetails={
          primaryPartyIdError instanceof Error ? primaryPartyIdError.message : undefined
        }
      />
    );
  }

  if (primaryPartyIdIsError || directoryEntryIsError) {
    return (
      <DirectoryFailed
        errorMessage={`${entryName} was not registered. Something went wrong.`}
        errorDetails={
          directoryEntryError instanceof Error ? directoryEntryError.message : undefined
        }
      />
    );
  }

  const directoryEntryOwner = directoryEntry.entryContract?.payload.user;

  if (primaryPartyId !== directoryEntryOwner) {
    return (
      <DirectoryFailed
        errorMessage={`${entryName} was not registered. It was claimed by someone else.`}
      />
    );
  }

  return <DirectoryReady entryName={entryName} />;
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
