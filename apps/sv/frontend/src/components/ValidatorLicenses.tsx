import { Loading, PartyId, SvClientProvider } from 'common-frontend';
import DateDisplay from 'common-frontend/lib/components/DateDisplay';
import React from 'react';

import { Chip, Stack, Table, TableContainer, TableHead, Typography } from '@mui/material';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';

import { Party } from '@daml/types';

import { useSvcInfos } from '../contexts/SvContext';
import { useValidatorLicenses } from '../hooks/useValidatorLicenses';
import { config } from '../utils';

const ValidatorLicenses: React.FC = () => {
  const validatorLicensesQuery = useValidatorLicenses();
  const svcInfosQuery = useSvcInfos();
  if (validatorLicensesQuery.isLoading || svcInfosQuery.isLoading) {
    return <Loading />;
  }

  if (validatorLicensesQuery.isError || svcInfosQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const validatorLicenses = validatorLicensesQuery.data.sort((a, b) => {
    return parseInt(b.metadata.createdAt) - parseInt(a.metadata.createdAt);
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
            {validatorLicenses.map(lincense => {
              return (
                <LicenseRow
                  key={`${lincense.payload.validator}-${lincense.payload.sponsor}`}
                  validator={lincense.payload.validator}
                  sponsor={lincense.payload.sponsor}
                  createdAt={new Date(parseInt(lincense.metadata.createdAt) / 1000)}
                  sv={svcInfosQuery.data!.svPartyId}
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
        {<PartyId partyId={validator} className="validator-licenses-validator" />}
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
  return (
    <SvClientProvider url={config.services.sv.url}>
      <ValidatorLicenses />
    </SvClientProvider>
  );
};

export default ValidatorLicensesWithContexts;
