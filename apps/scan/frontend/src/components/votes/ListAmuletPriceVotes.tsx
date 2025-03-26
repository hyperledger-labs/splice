// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AmountDisplay,
  DateDisplay,
  ErrorDisplay,
  Loading,
  PartyId,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { useCallback } from 'react';

import {
  Box,
  Typography,
  TableContainer,
  TableCell,
  TableHead,
  TableRow,
  TableBody,
  Table,
} from '@mui/material';

import { useAmuletPriceVotes } from '../../hooks/useAmuletPriceVotes';
import { useDsoInfos } from '../../hooks/useDsoInfos';
import { useScanConfig } from '../../utils/config';

const ListAmuletPriceVotes: React.FC = () => {
  const config = useScanConfig();
  const dsoInfosQuery = useDsoInfos();
  const amuletPriceVotesQuery = useAmuletPriceVotes();

  const amuletName = config.spliceInstanceNames.amuletName;

  const getMemberName = useCallback(
    (partyId: string) => {
      const member = dsoInfosQuery.data?.dsoRules.payload.svs.get(partyId);
      return member ? member.name : '';
    },
    [dsoInfosQuery.data]
  );

  if (amuletPriceVotesQuery.isLoading || dsoInfosQuery.isLoading) {
    return <Loading />;
  }

  if (amuletPriceVotesQuery.isError || dsoInfosQuery.isError) {
    return <ErrorDisplay message={'Could not retrieve amulet price votes'} />;
  }

  const amuletPriceVotes = amuletPriceVotesQuery.data;

  return (
    <Box mt={4} sx={{ borderBottom: 1, borderColor: 'divider' }}>
      <Typography mt={6} variant="h4">
        Desired {amuletName} Prices of Super Validators
      </Typography>
      <TableContainer>
        <Table style={{ tableLayout: 'fixed' }} className="scan-amulet-price-table">
          <TableHead>
            <TableRow>
              <TableCell>Super Validator</TableCell>
              <TableCell>Super Validator Party ID</TableCell>
              <TableCell>Desired {amuletName} Price</TableCell>
              <TableCell>Last Updated At</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {amuletPriceVotes?.map(vote => (
              <TableRow key={vote.sv} className="amulet-price-table-row">
                <TableCell>{getMemberName(vote.sv)}</TableCell>
                <TableCell>
                  <PartyId partyId={vote.sv} className="sv-party" />
                </TableCell>
                <TableCell className="amulet-price">
                  {vote.amuletPrice ? (
                    <AmountDisplay amount={vote.amuletPrice} currency="USDUnit" />
                  ) : (
                    'Not Set'
                  )}
                </TableCell>
                <TableCell>
                  <DateDisplay datetime={vote.lastUpdatedAt.toISOString()} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
};

export default ListAmuletPriceVotes;
