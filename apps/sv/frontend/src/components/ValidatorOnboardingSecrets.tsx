// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  DateDisplay,
  DisableConditionally,
  Loading,
  SvClientProvider,
} from '@lfdecentralizedtrust/splice-common-frontend';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import { useMutation } from '@tanstack/react-query';
import React, { useState, useCallback } from 'react';

import {
  Button,
  IconButton,
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

const VALID_PARTY_ID_REGEX = /^[^-]+-[^-]+-\d+$/;

const ValidatorOnboardingSecrets: React.FC = () => {
  const ONBOARDING_SECRET_EXPIRY_IN_SECOND = 172800; // We allow validator to be onboarded in 48 hours
  const { prepareValidatorOnboarding } = useSvAdminClient();
  const validatorOnboardingsQuery = useValidatorOnboardings();

  const [partyHint, setPartyHint] = useState('');

  const prepareOnboardingMutation = useMutation({
    mutationFn: (partyHint: string) => {
      return prepareValidatorOnboarding(ONBOARDING_SECRET_EXPIRY_IN_SECOND, partyHint);
    },
  });

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
          error={partyHint === '' || !VALID_PARTY_ID_REGEX.test(partyHint)}
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
            disabled: !VALID_PARTY_ID_REGEX.test(partyHint),
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
