// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  ConfirmationDialog,
  DateDisplay,
  DisableConditionally,
  Loading,
  SvClientProvider,
} from '@lfdecentralizedtrust/splice-common-frontend';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import React, { useState, useCallback } from 'react';

import {
  Box,
  Button,
  IconButton,
  Paper,
  Stack,
  Table,
  TableContainer,
  TableHead,
  TextField,
  Typography,
} from '@mui/material';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
import { useValidatorOnboardings } from '../hooks/useValidatorOnboardings';
import { useSvConfig } from '../utils';
import { useNetworkInstanceName } from '../hooks';
import dayjs from 'dayjs';
import {
  dateTimeFormatISO,
  getUTCWithOffset,
} from '@lfdecentralizedtrust/splice-common-frontend-utils';

const VALID_PARTY_HINT_REGEX = /^[^-]+-[^-]+-\d+$/;
const VALID_PARTY_ID_REGEX = /^[A-Za-z0-9_-]{1,185}::[A-Fa-f0-9]{68}$/;
const MAX_PARTY_ID_LENGTH = 255;

const isValidPartyId = (partyId: string): boolean =>
  VALID_PARTY_ID_REGEX.test(partyId) && partyId.length <= MAX_PARTY_ID_LENGTH;

const ValidatorOnboardingSecrets: React.FC = () => {
  const ONBOARDING_SECRET_EXPIRY_IN_SECOND = 172800; // We allow validator to be onboarded in 48 hours
  const { prepareValidatorOnboarding, grantValidatorLicense } = useSvAdminClient();
  const validatorOnboardingsQuery = useValidatorOnboardings();
  const queryClient = useQueryClient();

  const [partyHint, setPartyHint] = useState('');
  const [partyAddress, setPartyAddress] = useState('');
  const [grantLicenseDialogOpen, setGrantLicenseDialogOpen] = useState(false);

  const prepareOnboardingMutation = useMutation({
    mutationFn: (partyHint: string) => {
      return prepareValidatorOnboarding(ONBOARDING_SECRET_EXPIRY_IN_SECOND, partyHint);
    },
  });

  const grantLicenseMutation = useMutation({
    mutationFn: (partyId: string) => {
      return grantValidatorLicense(partyId);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['listValidatorLicenses'] });
    },
  });

  const handleGrantLicenseConfirm = () => {
    grantLicenseMutation.mutateAsync(partyAddress).then(() => {
      setPartyAddress('');
      setGrantLicenseDialogOpen(false);
    });
  };

  const handleGrantLicenseClose = () => {
    setGrantLicenseDialogOpen(false);
  };

  const copyPartyAddress = useCallback(() => {
    navigator.clipboard.writeText(partyAddress);
  }, [partyAddress]);

  if (validatorOnboardingsQuery.isPending) {
    return <Loading />;
  }

  if (validatorOnboardingsQuery.isError) {
    return <p>Error, something went wrong while fetching onboarding secrets.</p>;
  }

  const validatorOnboardings = validatorOnboardingsQuery.data.toSorted(
    (a, b) =>
      new Date(b.contract.payload.expiresAt).valueOf() -
      new Date(a.contract.payload.expiresAt).valueOf()
  );

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={4} variant="h4">
        Validator Onboarding Secrets
      </Typography>

      {prepareOnboardingMutation.isError && (
        <Typography variant="body1">
          Error, something went wrong while preparing onboarding secret.
        </Typography>
      )}

      <Stack direction="column" mb={4} spacing={1}>
        <Typography variant="h6">Party Hint</Typography>
        <TextField
          error={partyHint === '' || !VALID_PARTY_HINT_REGEX.test(partyHint)}
          autoComplete="off"
          id="create-party-hint"
          placeholder="<organization>-<function>-<enumerator>"
          inputProps={{ 'data-testid': 'create-party-hint' }}
          onChange={e => setPartyHint(e.target.value)}
          value={partyHint}
        />
      </Stack>

      <DisableConditionally
        conditions={[
          { disabled: prepareOnboardingMutation.isPending, reason: 'Loading...' },
          { disabled: partyHint === '', reason: 'No Party Hint' },
          {
            disabled: !VALID_PARTY_HINT_REGEX.test(partyHint),
            reason: 'Party Hint is invalid',
            severity: 'warning',
          },
        ]}
      >
        <Button
          id="create-validator-onboarding-secret"
          data-testid="create-validator-onboarding-secret"
          variant="pill"
          fullWidth
          size="large"
          onClick={() => {
            prepareOnboardingMutation.mutateAsync(partyHint).then(() => setPartyHint(''));
          }}
        >
          Create a validator onboarding secret
        </Button>
      </DisableConditionally>

      {grantLicenseMutation.isError && (
        <Typography variant="body1" color="error">
          Error: {grantLicenseMutation.error instanceof Error
            ? grantLicenseMutation.error.message
            : 'Something went wrong while granting validator license'}
        </Typography>
      )}

      <Stack direction="column" mb={4} spacing={1}>
        <Typography variant="h6">Grant Validator License</Typography>
        <TextField
          error={partyAddress !== '' && !isValidPartyId(partyAddress)}
          autoComplete="off"
          id="grant-license-party-address"
          placeholder="<name>::<identifier>"
          inputProps={{ 'data-testid': 'grant-license-party-address' }}
          onChange={e => setPartyAddress(e.target.value)}
          value={partyAddress}
        />
      </Stack>

      <DisableConditionally
        conditions={[
          { disabled: grantLicenseMutation.isPending, reason: 'Loading...' },
          { disabled: partyAddress === '', reason: 'No Party Address' },
          {
            disabled: !isValidPartyId(partyAddress),
            reason: 'Party Address is invalid',
            severity: 'warning',
          },
        ]}
      >
        <Button
          id="grant-validator-license"
          data-testid="grant-validator-license"
          variant="pill"
          fullWidth
          size="large"
          onClick={() => setGrantLicenseDialogOpen(true)}
        >
          Grant Validator License
        </Button>
      </DisableConditionally>

      <ConfirmationDialog
        showDialog={grantLicenseDialogOpen}
        onClose={handleGrantLicenseClose}
        onAccept={handleGrantLicenseConfirm}
        title="Confirm Grant Validator License"
        attributePrefix="grant-license"
      >
        <Stack spacing={2}>
          <Typography variant="body1">
            Are you sure you want to grant a validator license to the following party?
          </Typography>
          <Box>
            <Typography variant="caption" color="text.secondary" sx={{ mb: 0.5, display: 'block' }}>
              Party Address:
            </Typography>
            <Paper
              variant="outlined"
              sx={{
                p: 1.5,
                display: 'flex',
                alignItems: 'center',
                gap: 1,
                fontFamily: 'monospace',
                fontSize: '0.875rem',
                wordBreak: 'break-all',
              }}
            >
              <Box sx={{ flex: 1 }}>{partyAddress}</Box>
              <IconButton
                onClick={copyPartyAddress}
                size="small"
                sx={{ flexShrink: 0 }}
                title="Copy to clipboard"
              >
                <ContentCopyIcon fontSize="small" />
              </IconButton>
            </Paper>
          </Box>
        </Stack>
      </ConfirmationDialog>

      <TableContainer>
        <Table
          sx={{
            display: 'grid',
            gridTemplateColumns: 'max-content minmax(0, 1fr) max-content max-content',
            alignContent: 'center',
          }}
          className="onboarding-secret-table"
        >
          <TableHead sx={{ display: 'contents' }}>
            <TableRow sx={{ display: 'contents' }}>
              <TableCell>Party Hint</TableCell>
              <TableCell>Onboarding Secret</TableCell>
              <TableCell>Expires At</TableCell>
              <TableCell>{/* Copy icon column */}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody sx={{ display: 'contents' }}>
            {validatorOnboardings.map(onboarding => (
              <OnboardingRow
                key={onboarding.encodedSecret}
                partyHint={onboarding.partyHint}
                secret={onboarding.encodedSecret}
                expiresAt={onboarding.contract.payload.expiresAt}
              />
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  );
};

export const onboardingInfo = (
  { partyHint, secret, expiresAt }: OnboardingRowProps,
  instanceName?: string
): string => {
  let info = '';

  if (partyHint) {
    info += `${partyHint}\n`;
  }

  info += `Network: ${instanceName ?? 'localnet'}\n`;

  info += 'SPONSOR_SV_URL\n';
  info += window.location.origin;
  info += '\n\n';

  info += 'Secret\n';
  info += secret;
  info += '\n\n';

  info += 'Expiration\n';
  info += `${dayjs(expiresAt).format(dateTimeFormatISO)} (${getUTCWithOffset()})`;

  return info;
};

interface OnboardingRowProps {
  partyHint?: string;
  secret: string;
  expiresAt: string;
}

const OnboardingRow: React.FC<OnboardingRowProps> = props => {
  const networkInstanceName = useNetworkInstanceName();

  const copySecret = useCallback(() => {
    navigator.clipboard.writeText(props.secret);
  }, [props]);

  const copyOnboardingInfo = useCallback(() => {
    navigator.clipboard.writeText(onboardingInfo(props, networkInstanceName));
  }, [props, networkInstanceName]);

  return (
    <TableRow sx={{ display: 'contents' }} className="onboarding-secret-table-row">
      <TableCell sx={{ display: 'flex', alignItems: 'center' }}>
        <Typography noWrap>{props.partyHint ?? '---'}</Typography>
      </TableCell>
      <TableCell sx={{ display: 'flex', alignItems: 'center' }}>
        <Typography
          sx={{
            overflowWrap: 'break-word',
            wordBreak: 'break-word',
          }}
          className="onboarding-secret-table-secret"
        >
          {props.secret}
        </Typography>
        <IconButton onClick={copySecret}>
          <ContentCopyIcon fontSize={'small'} />
        </IconButton>
      </TableCell>
      <TableCell sx={{ display: 'flex', alignItems: 'center' }}>
        <DateDisplay datetime={props.expiresAt} />
      </TableCell>
      <TableCell sx={{ display: 'flex', alignItems: 'center' }}>
        <IconButton onClick={copyOnboardingInfo}>
          <ContentCopyIcon fontSize={'small'} />
        </IconButton>
      </TableCell>
    </TableRow>
  );
};

const ValidatorOnboardingSecretsWithContexts: React.FC = () => {
  const config = useSvConfig();
  return (
    <SvClientProvider url={config.services.sv.url}>
      <ValidatorOnboardingSecrets />
    </SvClientProvider>
  );
};

export default ValidatorOnboardingSecretsWithContexts;
