// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  DateDisplay,
  DisableConditionally,
  Loading,
  SvClientProvider,
  CopyableTypography,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { useMutation } from '@tanstack/react-query';
import React from 'react';

import { Button, Stack, Table, TableContainer, TableHead, Typography } from '@mui/material';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
import { useValidatorOnboardings } from '../hooks/useValidatorOnboardings';
import { useSvConfig } from '../utils';

const ValidatorOnboardingSecrets: React.FC = () => {
  const ONBOARDING_SECRET_EXPIRY_IN_SECOND = 172800; // We allow validator to be onboarded in 48 hours
  const { prepareValidatorOnboarding } = useSvAdminClient();
  const validatorOnboardingsQuery = useValidatorOnboardings();

  const prepareOnboardingMutation = useMutation({
    mutationFn: () => {
      return prepareValidatorOnboarding(ONBOARDING_SECRET_EXPIRY_IN_SECOND);
    },
  });

  if (validatorOnboardingsQuery.isPending) {
    return <Loading />;
  }

  if (validatorOnboardingsQuery.isError) {
    return <p>Error, something went wrong while fetching onboarding secrets.</p>;
  }

  const validatorOnboardings = validatorOnboardingsQuery.data.sort((a, b) => {
    return (
      new Date(b.contract.payload.expiresAt).valueOf() -
      new Date(a.contract.payload.expiresAt).valueOf()
    );
  });

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

      <DisableConditionally
        conditions={[{ disabled: prepareOnboardingMutation.isPending, reason: 'Loading...' }]}
      >
        <Button
          id="create-validator-onboarding-secret"
          variant="pill"
          fullWidth
          disabled={prepareOnboardingMutation.isPending}
          size="large"
          onClick={() => prepareOnboardingMutation.mutate()}
        >
          Create a validator onboarding secret
        </Button>
      </DisableConditionally>
      <TableContainer>
        <Table style={{ tableLayout: 'fixed' }} className="onboarding-secret-table">
          <TableHead>
            <TableRow>
              <TableCell>Expires At</TableCell>
              <TableCell>Onboarding Secret</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {validatorOnboardings.map(onboarding => {
              return (
                <OnboardingRow
                  key={onboarding.encodedSecret}
                  expiresAt={onboarding.contract.payload.expiresAt}
                  secret={onboarding.encodedSecret}
                />
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  );
};

interface OnboardingRowProps {
  expiresAt: string;
  secret: string;
}

const OnboardingRow: React.FC<OnboardingRowProps> = ({ expiresAt, secret }) => {
  return (
    <TableRow className="onboarding-secret-table-row">
      <TableCell>
        <DateDisplay datetime={expiresAt} />
      </TableCell>
      <TableCell>
        <CopyableTypography text={secret} className="onboarding-secret-table-secret" />
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
