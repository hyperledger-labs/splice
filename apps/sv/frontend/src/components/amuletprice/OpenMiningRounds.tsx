// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AmountDisplay,
  DateDisplay,
  Loading,
  SvClientProvider,
} from '@lfdecentralizedtrust/splice-common-frontend';
import BigNumber from 'bignumber.js';
import React from 'react';

import {
  Table,
  TableContainer,
  TableHead,
  TableBody,
  TableCell,
  TableRow,
  Typography,
} from '@mui/material';

import { useOpenMiningRounds } from '../../hooks/useOpenMiningRounds';
import { useSvConfig } from '../../utils';

const OpenMiningRounds: React.FC = () => {
  const config = useSvConfig();
  const openMiningRoundsQuery = useOpenMiningRounds();

  if (openMiningRoundsQuery.isLoading) {
    return <Loading />;
  }

  if (openMiningRoundsQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const sortedRounds = openMiningRoundsQuery.data.sort(
    (a, b) => parseInt(b.payload.round.number) - parseInt(a.payload.round.number)
  );

  return (
    <>
      <Typography mt={6} variant="h4">
        Open Mining Rounds
      </Typography>
      <TableContainer>
        <Table style={{ tableLayout: 'fixed' }} className="sv-amulet-price-table">
          <TableHead>
            <TableRow>
              <TableCell>Round</TableCell>
              <TableCell>{config.spliceInstanceNames.amuletName} Price</TableCell>
              <TableCell>Opens At</TableCell>
              <TableCell>Target Closes At</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {sortedRounds.map(round => {
              return (
                <OpenMiningRoundRow
                  key={round.payload.round.number}
                  round={Number(round.payload.round.number)}
                  amuletPrice={new BigNumber(round.payload.amuletPrice)}
                  opensAt={new Date(round.payload.opensAt)}
                  targetClosesAt={new Date(round.payload.targetClosesAt)}
                />
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </>
  );
};

interface OpenMiningRoundRowProps {
  round: number;
  amuletPrice: BigNumber;
  opensAt: Date;
  targetClosesAt: Date;
}

const OpenMiningRoundRow: React.FC<OpenMiningRoundRowProps> = ({
  round,
  amuletPrice,
  opensAt,
  targetClosesAt,
}) => {
  return (
    <TableRow className="open-mining-round-row">
      <TableCell className="round-number">{round}</TableCell>
      <TableCell className="amulet-price">
        <AmountDisplay amount={amuletPrice} currency="USDUnit" />
      </TableCell>
      <TableCell>
        <DateDisplay datetime={opensAt.toISOString()} />
      </TableCell>
      <TableCell>
        <DateDisplay datetime={targetClosesAt.toISOString()} />
      </TableCell>
    </TableRow>
  );
};

const OpenMiningRoundsWithContexts: React.FC = () => {
  const config = useSvConfig();
  return (
    <SvClientProvider url={config.services.sv.url}>
      <OpenMiningRounds />
    </SvClientProvider>
  );
};

export default OpenMiningRoundsWithContexts;
