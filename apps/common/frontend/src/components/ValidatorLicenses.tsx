// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { UseInfiniteQueryResult, UseQueryResult } from '@tanstack/react-query';
import { DateDisplay, DsoInfo, Loading, PartyId, ViewMoreButton } from 'common-frontend';
import { Contract } from 'common-frontend-utils';
import React from 'react';

import { Chip, Stack, Table, TableContainer, TableHead, Typography } from '@mui/material';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';

import { ValidatorLicense } from '@daml.js/splice-amulet-0.1.6/lib/Splice/ValidatorLicense';
import { Party } from '@daml/types';

export interface ValidatorLicensesPage {
  validatorLicenses: Contract<ValidatorLicense>[];
  after?: number;
}

interface ValidatorLicensesProps {
  validatorLicensesQuery: UseInfiniteQueryResult<ValidatorLicensesPage>;
  dsoInfosQuery: UseQueryResult<DsoInfo>;
}

const ValidatorLicenses: React.FC<ValidatorLicensesProps> = ({
  validatorLicensesQuery,
  dsoInfosQuery,
}) => {
  if (validatorLicensesQuery.isLoading || dsoInfosQuery.isLoading) {
    return <Loading />;
  }

  if (validatorLicensesQuery.isError || dsoInfosQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const loadedValidatorLicenses = validatorLicensesQuery.data.pages.flatMap(
    page => page.validatorLicenses
  );

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
            {loadedValidatorLicenses.map(license => {
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
      <ViewMoreButton
        label={
          validatorLicensesQuery.isFetchingNextPage
            ? 'Loading more...'
            : validatorLicensesQuery.hasNextPage
            ? 'View More'
            : 'Nothing more to load'
        }
        loadMore={() => validatorLicensesQuery.fetchNextPage()}
        disabled={!validatorLicensesQuery.hasNextPage}
        idSuffix="validator-licenses"
      />
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

export default ValidatorLicenses;
