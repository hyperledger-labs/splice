import { AmountDisplay, IntervalDisplay, SubscriptionButton } from 'common-frontend';
import React, { useState } from 'react';

import { CheckCircleOutline, ErrorOutline } from '@mui/icons-material';
import { Box, Button, Stack, Typography, styled } from '@mui/material';

import Searchbar from '../components/Searchbar';
import { useDirectoryInstall, useLookupEntryByName, useRequestEntry } from '../hooks';
import { config, ENTRY_NAME_SUFFIX, toFullEntryName } from '../utils';

type NameLookupStatus = 'available' | 'taken' | 'loading';

const entryNameRegex = new RegExp(`^[a-z0-9_-]{1,${40 - ENTRY_NAME_SUFFIX.length}}$`);
const isEntryNameValid = (name: string) => {
  return entryNameRegex.test(name);
};

const RequestDirectoryEntry: React.FC = () => {
  const [entryName, setEntryName] = useState<string>('');

  const [displayValidationResult, setDisplayValidationResult] = useState(false);
  const { data: entryLookupResult, isLoading } = useLookupEntryByName(entryName, ENTRY_NAME_SUFFIX);

  const nameLookupStatus: NameLookupStatus = isLoading
    ? 'loading'
    : entryLookupResult?.entryContract === undefined
    ? 'available'
    : 'taken';
  const searchButtonDisabled = nameLookupStatus === 'loading';

  return (
    <Stack justifyContent="center" mt={2} spacing={2}>
      <Typography variant="body1">Register your name in the Canton Network</Typography>
      <Typography variant="h3">Search for the name you’d like to register</Typography>
      <Stack direction="row" spacing={2}>
        <Searchbar
          sx={{ flexGrow: '1' }}
          value={entryName}
          onKeyDown={event => {
            setDisplayValidationResult(event.key === 'Enter');
          }}
          onChange={event => setEntryName(event.target.value)}
          id="entry-name-field"
        />
        <Button
          variant="pill"
          id="search-entry-button"
          disabled={searchButtonDisabled}
          onClick={() => setDisplayValidationResult(true)}
        >
          Search
        </Button>
      </Stack>
      {displayValidationResult && (
        <SubscriptionBar entryName={entryName} nameLookupStatus={nameLookupStatus} />
      )}
    </Stack>
  );
};

const SubscriptionBar: React.FC<{ entryName: string; nameLookupStatus: NameLookupStatus }> = ({
  entryName,
  nameLookupStatus,
}) => {
  const { mutateAsync: requestEntry } = useRequestEntry();
  const { data: directoryInstall } = useDirectoryInstall();

  if (nameLookupStatus === 'loading' || !directoryInstall) {
    return <></>;
  }

  var message, icon, additionalContent;

  const entryFee = directoryInstall.payload.entryFee;
  const entryInterval = directoryInstall.payload.entryLifetime.microseconds;

  if (!isEntryNameValid(entryName)) {
    message =
      'The provided entry name has an invalid format. Maximum 40 characters(including suffix), a-z, 0-9, - and _ are supported.';
    icon = <ErrorOutline id="unavailable-icon" color="error" />;
  } else if (nameLookupStatus === 'available') {
    message = 'is available!';
    icon = <CheckCircleOutline color="success" />;
    additionalContent = (
      <Stack direction="row" alignItems="center">
        <Typography display="inline" fontWeight="bold">
          <AmountDisplay amount={entryFee} currency="USD" />
        </Typography>
        &nbsp;
        <Typography display="inline">
          every <IntervalDisplay microseconds={entryInterval} />
        </Typography>
        <SubscriptionButton
          sx={{ marginLeft: 4 }}
          variant="pill"
          id="request-entry-with-sub-button"
          text="Subscribe Now"
          createPaymentRequest={() => requestEntry({ entryName, suffix: ENTRY_NAME_SUFFIX })}
          redirectPath={`/post-payment?entryName=${encodeURIComponent(entryName)}`}
          walletPath={config.services.wallet.uiUrl}
        />
      </Stack>
    );
  } else if (nameLookupStatus === 'taken') {
    message = 'is not available. Please try a different name.';
    icon = <ErrorOutline id="unavailable-icon" color="error" />;
  }

  return (
    <SubscriptionBarStyled direction="row">
      <Stack direction="row" alignItems="center">
        <Box display="flex" marginRight={1} alignItems="center">
          {icon}
        </Box>
        <Typography display="inline" fontWeight="bold">
          {toFullEntryName(entryName, ENTRY_NAME_SUFFIX)}
        </Typography>
        &nbsp;
        <Typography id="entry-name-validation-message" display="inline">
          {' '}
          {message}
        </Typography>
      </Stack>
      {additionalContent}
    </SubscriptionBarStyled>
  );
};

const SubscriptionBarStyled = styled(Stack)(({ theme }) => ({
  border: `2px solid ${theme.palette.colors.neutral[30]}`,
  borderRadius: '4px',
  padding: theme.spacing(2),
  alignItems: 'center',
  justifyContent: 'space-between',
}));

export default RequestDirectoryEntry;
