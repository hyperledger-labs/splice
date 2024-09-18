// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DateDisplay, Loading, PartyId, SvClientProvider } from 'common-frontend';
import React from 'react';

import { Chip, Stack, Table, TableContainer, TableHead, Typography } from '@mui/material';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';

import { Party } from '@daml/types';

import { useDsoInfos } from '../contexts/SvContext';
import { useValidatorLicenses } from '../hooks/useValidatorLicenses';
import { useSvConfig } from '../utils';

const ValidatorLicenses: React.FC = () => {
  const validatorLicensesQuery = useValidatorLicenses();
  const dsoInfosQuery = useDsoInfos();
  if (validatorLicensesQuery.isLoading || dsoInfosQuery.isLoading) {
    return <Loading />;
  }

  if (validatorLicensesQuery.isError || dsoInfosQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const validatorLicenses = validatorLicensesQuery.data.sort((a, b) => {
    const createdAtA = a.createdAt;
    const createdAtB = b.createdAt;
    if (createdAtA === createdAtB) {
      return 0;
    } else if (createdAtA < createdAtB) {
      return 1;
    } else {
      return -1;
    }
  });

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Validator Licenses
      </Typography>
      <TableContainer>
        <Table style={{ tableLayout: 'fixed' }} className="validator-licenses-table">
          <TableHead>
            <TableRow>
              <TableCell>Created at</TableCell>
              <TableCell>Validator</TableCell>
              <TableCell>Sponsor</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {validatorLicenses.map(license => {
              return (
                <LicenseRow
                  key={license.contractId}
                  validator={license.payload.validator}
                  sponsor={license.payload.sponsor}
                  createdAt={new Date(license.createdAt)}
                  sv={dsoInfosQuery.data!.svPartyId}
                />
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  );
};

interface LicenseRowProps {
  validator: Party;
  sponsor: Party;
  createdAt: Date;
  sv: Party;
}

const LicenseRow: React.FC<LicenseRowProps> = ({ validator, sponsor, createdAt, sv }) => {
  const sponsoredByThisSv = sponsor === sv;
  return (
    <TableRow className="validator-licenses-table-row">
      <TableCell>
        <DateDisplay datetime={createdAt.toISOString()} />
      </TableCell>
      <TableCell>
        <PartyId partyId={validator} className="validator-licenses-validator" />
      </TableCell>
      <TableCell>
        <Stack direction="row" spacing={1}>
          <PartyId partyId={sponsor} className="validator-licenses-sponsor" />
          {sponsoredByThisSv && <Chip label="THIS SV" color="primary" size="small" />}
        </Stack>
      </TableCell>
    </TableRow>
  );
};

const ValidatorLicensesWithContexts: React.FC = () => {
  const config = useSvConfig();
  return (
    <SvClientProvider url={config.services.sv.url}>
      <ValidatorLicenses />
    </SvClientProvider>
  );
};

export default ValidatorLicensesWithContexts;
