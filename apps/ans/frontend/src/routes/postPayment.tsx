// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import React from 'react';
import { Link, useSearchParams } from 'react-router';

import { CloseRounded, DoneRounded } from '@mui/icons-material';
import { Button, Stack, Typography } from '@mui/material';

import { useLookupAnsEntryByName } from '../hooks';
import { usePrimaryParty } from '../hooks/queries/usePrimaryParty';
import { toEntryNameSuffix, toFullEntryName, useAnsConfig } from '../utils';

export const PostPayment: React.FC = () => {
  const config = useAnsConfig();
  const ENTRY_NAME_SUFFIX = toEntryNameSuffix(config.spliceInstanceNames.nameServiceNameAcronym);
  const [searchParams] = useSearchParams();
  const entryName = searchParams.get('entryName') || '';

  const primaryPartyId = usePrimaryParty();
  const {
    data: ansEntry,
    isLoading: ansEntryIsLoading,
    isError: ansEntryIsError,
    error: ansEntryError,
  } = useLookupAnsEntryByName(
    toFullEntryName(entryName, ENTRY_NAME_SUFFIX),
    !!primaryPartyId,
    true,
    10
  );

  if (!entryName) {
    console.error('PostPayment rendered without entryName.');
    return <></>;
  }

  const fullEntryName = toFullEntryName(entryName, ENTRY_NAME_SUFFIX);

  const ansEntryOwner = ansEntry?.user;

  if (!primaryPartyId || !ansEntryOwner || ansEntryIsLoading) {
    return <AnsLoading fullEntryName={fullEntryName} />;
  }

  if (ansEntryIsError) {
    return (
      <AnsFailed
        errorMessage={`${fullEntryName} was not registered. Something went wrong.`}
        errorDetails={ansEntryError instanceof Error ? ansEntryError.message : undefined}
      />
    );
  }

  if (primaryPartyId !== ansEntryOwner) {
    return (
      <AnsFailed
        errorMessage={`${fullEntryName} was not registered. It was claimed by someone else.`}
      />
    );
  }

  return <AnsReady fullEntryName={fullEntryName} />;
};

interface AnsProps {
  fullEntryName: string;
}

const AnsLoading: React.FC<AnsProps> = ({ fullEntryName }) => {
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

const AnsFailed: React.FC<{ errorMessage: string; errorDetails?: string }> = ({
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
      <YourAnsEntriesButton />
    </Stack>
  );
};

const AnsReady: React.FC<AnsProps> = ({ fullEntryName }) => {
  const config = useAnsConfig();
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
          {fullEntryName} is now registered in {config.spliceInstanceNames.networkName}.
        </Typography>
        <YourAnsEntriesButton />
      </Stack>
      <UnverifiedInfo fullEntryName={fullEntryName} />
    </Stack>
  );
};

const UnverifiedInfo: React.FC<AnsProps> = ({ fullEntryName }) => {
  const config = useAnsConfig();
  const nsn = config.spliceInstanceNames.nameServiceNameAcronym.toLowerCase();
  const isUnverified = fullEntryName.endsWith(`.unverified.${nsn}`);
  if (isUnverified) {
    return (
      <Stack>
        <Typography variant="body1" color="colors.neutral.80">
          Why <i>unverified</i>.{nsn}?
        </Typography>
        <Typography variant="body1" color="colors.neutral.80">
          There is no verification of a user’s identity for
          {nsn.toUpperCase()} entry names at this point so entries are required to include
          ".unverified" in the name to allow for the later addition of verified entries.
        </Typography>
      </Stack>
    );
  } else {
    return null;
  }
};

const YourAnsEntriesButton: React.FC = () => {
  const config = useAnsConfig();
  return (
    <Link to="/">
      <Button variant="pill" id="ans-entries-button">
        Go to your {config.spliceInstanceNames.nameServiceNameAcronym.toUpperCase()} Entries
      </Button>
    </Link>
  );
};

const statusIconStyle = { width: '80px', height: '80px', border: '3px solid', borderRadius: '50%' };

export default PostPayment;
