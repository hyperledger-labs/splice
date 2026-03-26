// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import React, { SelectHTMLAttributes, useMemo, useState } from 'react';

import { FormControl, NativeSelect, Stack, TextField, Typography } from '@mui/material';

import { ValidatorLicense } from '@daml.js/splice-amulet/lib/Splice/ValidatorLicense/module';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { ContractId } from '@daml/types';

import { useValidatorLicenses } from '../../../hooks/useValidatorLicenses';

const RevokeValidatorLicense: React.FC<{
  chooseAction: (action: ActionRequiringConfirmation) => void;
}> = ({ chooseAction }) => {
  const validatorLicensesQuery = useValidatorLicenses(1000);
  const [selectedValidator, setSelectedValidator] = useState<string>('');
  const [reason, setReason] = useState<string>('');

  const licenses = useMemo(() => {
    if (!validatorLicensesQuery.data) return [];
    return validatorLicensesQuery.data.pages.flatMap(page => page.validatorLicenses);
  }, [validatorLicensesQuery.data]);

  if (validatorLicensesQuery.isLoading) {
    return <Loading />;
  }

  if (validatorLicensesQuery.isError) {
    return <p>Error: {JSON.stringify(validatorLicensesQuery.error)}</p>;
  }

  function updateAction(validatorParty: string, withdrawReason: string) {
    const license = licenses.find(l => l.payload.validator === validatorParty);
    if (!license || !withdrawReason) return;

    chooseAction({
      tag: 'ARC_ValidatorLicense',
      value: {
        validatorLicenseCid: license.contractId as ContractId<ValidatorLicense>,
        validatorLicenseAction: {
          tag: 'VLRARC_WithdrawValidatorLicense',
          value: { reason: withdrawReason },
        },
      },
    });
  }

  function setValidatorAction(validatorParty: string) {
    setSelectedValidator(validatorParty);
    updateAction(validatorParty, reason);
  }

  function setReasonAction(withdrawReason: string) {
    setReason(withdrawReason);
    updateAction(selectedValidator, withdrawReason);
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Validator</Typography>
      <FormControl fullWidth>
        <NativeSelect
          inputProps={
            {
              id: 'display-validators',
              'data-testid': 'display-validators',
            } as SelectHTMLAttributes<HTMLSelectElement>
          }
          value={selectedValidator}
          onChange={e => setValidatorAction(e.target.value)}
        >
          <option>No validator selected</option>
          {licenses.map((license, index) => (
            <option
              key={'validator-option-' + index}
              value={license.payload.validator}
              data-testid={'display-validators-option'}
            >
              {license.payload.validator}
            </option>
          ))}
        </NativeSelect>
      </FormControl>

      <Typography variant="h6">Reason</Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <TextField
          id="revoke-validator-reason"
          inputProps={{ 'data-testid': 'revoke-validator-reason' }}
          onChange={e => setReasonAction(e.target.value)}
          value={reason}
          placeholder="Reason for revoking the validator license"
        />
      </FormControl>
    </Stack>
  );
};

export default RevokeValidatorLicense;
