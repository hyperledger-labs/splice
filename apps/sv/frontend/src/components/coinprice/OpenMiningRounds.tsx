import BigNumber from 'bignumber.js';
import { AmountDisplay, Loading, SvClientProvider } from 'common-frontend';
import DateDisplay from 'common-frontend/lib/components/DateDisplay';
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
import { config } from '../../utils';

const OpenMiningRounds: React.FC = () => {
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
        <Table style={{ tableLayout: 'fixed' }} className="sv-coin-price-table">
          <TableHead>
            <TableRow>
              <TableCell>Round</TableCell>
              <TableCell>Canton Coin Price</TableCell>
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
                  coinPrice={new BigNumber(round.payload.coinPrice)}
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
  coinPrice: BigNumber;
  opensAt: Date;
  targetClosesAt: Date;
}

const OpenMiningRoundRow: React.FC<OpenMiningRoundRowProps> = ({
  round,
  coinPrice,
  opensAt,
  targetClosesAt,
}) => {
  return (
    <TableRow className="open-mining-round-row">
      <TableCell className="round-number">{round}</TableCell>
      <TableCell className="coin-price">
        <AmountDisplay amount={coinPrice} currency="USD" />
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
  return (
    <SvClientProvider url={config.services.sv.url}>
      <OpenMiningRounds />
    </SvClientProvider>
  );
};

export default OpenMiningRoundsWithContexts;
