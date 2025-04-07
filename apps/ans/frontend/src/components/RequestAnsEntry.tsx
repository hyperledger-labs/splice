// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AmountDisplay,
  DisableConditionally,
  IntervalDisplay,
  SubscriptionButton,
} from '@lfdecentralizedtrust/splice-common-frontend';
import React, { useState } from 'react';
import { useDebouncedCallback } from 'use-debounce';

import { CheckCircleOutline, ErrorOutline } from '@mui/icons-material';
import { Box, Button, Stack, Typography, styled } from '@mui/material';

import Searchbar from '../components/Searchbar';
import { useRequestEntry, useLookupAnsEntryByName, useGetAnsRules } from '../hooks';
import { usePrimaryParty } from '../hooks/queries/usePrimaryParty';
import { toEntryNameSuffix, toFullEntryName, useAnsConfig } from '../utils';

type NameLookupStatus = 'available' | 'taken' | 'loading';

const RequestAnsEntry: React.FC = () => {
  const config = useAnsConfig();
  const ENTRY_NAME_SUFFIX = toEntryNameSuffix(config.spliceInstanceNames.nameServiceNameAcronym);
  const [entryName, setEntryName] = useState<string>('');
  const debounced = useDebouncedCallback(value => {
    setEntryName(value);
    setDisplayValidationResult(true);
  }, 500);

  const [displayValidationResult, setDisplayValidationResult] = useState(false);
  const primaryPartyId = usePrimaryParty();
  const { data: entryLookupResult, isLoading } = useLookupAnsEntryByName(
    toFullEntryName(entryName, ENTRY_NAME_SUFFIX),
    !!primaryPartyId
  );

  const nameLookupStatus: NameLookupStatus = isLoading
    ? 'loading'
    : entryLookupResult === null
    ? 'available'
    : 'taken';

  return (
    <Stack justifyContent="center" mt={2} spacing={2}>
      <Typography variant="body1">
        Register your name in {config.spliceInstanceNames.networkName}
      </Typography>
      <Typography variant="h3">Search for the name you’d like to register</Typography>
      <Stack direction="row" spacing={2}>
        <Searchbar
          sx={{ flexGrow: '1' }}
          onKeyDown={event => {
            if (event.key === 'Enter') {
              setDisplayValidationResult(true);
              debounced.flush();
            } else {
              setDisplayValidationResult(false);
            }
          }}
          onChange={event => debounced(event.target.value)}
          id="entry-name-field"
        />
        <DisableConditionally
          conditions={[{ disabled: nameLookupStatus === 'loading', reason: 'Loading...' }]}
        >
          <Button
            variant="pill"
            id="search-entry-button"
            onClick={() => {
              setDisplayValidationResult(true);
              debounced.flush();
            }}
          >
            Search
          </Button>
        </DisableConditionally>
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
  const config = useAnsConfig();
  const ENTRY_NAME_SUFFIX = toEntryNameSuffix(config.spliceInstanceNames.nameServiceNameAcronym);
  const { mutateAsync: requestEntry } = useRequestEntry();
  const { data: ansRules } = useGetAnsRules();

  const entryNameRegex = new RegExp(`^[a-z0-9_-]{1,${60 - ENTRY_NAME_SUFFIX.length}}$`);
  const isEntryNameValid = (name: string) => {
    return entryNameRegex.test(name);
  };

  if (nameLookupStatus === 'loading' || !ansRules) {
    return <></>;
  }

  var message, icon, additionalContent;

  const entryFee = ansRules.payload.config.entryFee;
  const entryInterval = ansRules.payload.config.entryLifetime;

  if (!isEntryNameValid(entryName)) {
    message =
      'The provided entry name has an invalid format. Maximum 60 characters(including suffix), a-z, 0-9, - and _ are supported.';
    icon = <ErrorOutline id="unavailable-icon" color="error" />;
  } else if (nameLookupStatus === 'available') {
    message = 'is available!';
    icon = <CheckCircleOutline color="success" />;
    additionalContent = (
      <Stack direction="row" alignItems="center">
        <Typography display="inline" fontWeight="bold">
          <AmountDisplay amount={entryFee} currency="USDUnit" />
        </Typography>
        &nbsp;
        <Typography display="inline">
          every <IntervalDisplay microseconds={entryInterval.microseconds} />
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

export default RequestAnsEntry;
