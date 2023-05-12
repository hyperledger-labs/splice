import BigNumber from 'bignumber.js';
import { AmountDisplay, Loading, PartyId, SvClientProvider } from 'common-frontend';
import DateDisplay from 'common-frontend/lib/components/DateDisplay';
import React from 'react';

import { Stack, Table, TableContainer, TableHead, Typography } from '@mui/material';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';

import { Numeric, Optional, Party } from '@daml/types';

import { useSvcInfos } from '../../contexts/SvContext';
import { useCoinPriceVotes } from '../../hooks/useCoinPriceVotes';
import { CoinPriceVote } from '../../models/models';
import { config } from '../../utils';

const DesiredCoinPrice: React.FC = () => {
  const coinPriceVotesQuery = useCoinPriceVotes();
  const svcInfosQuery = useSvcInfos();
  if (coinPriceVotesQuery.isLoading || svcInfosQuery.isLoading) {
    return <Loading />;
  }

  if (coinPriceVotesQuery.isError || svcInfosQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const svPartyId = svcInfosQuery.data!.svPartyId;

  const curSvCoinPriceVote: CoinPriceVote | undefined = coinPriceVotesQuery.data.find(
    v => v.sv === svPartyId
  );

  const maybeBigNumber = (maybeNumeric: Optional<Numeric>) => {
    return maybeNumeric !== null ? new BigNumber(maybeNumeric) : undefined;
  };

  const otherCoinPriceVotes = coinPriceVotesQuery.data
    .filter(v => v.sv !== svPartyId)
    .sort((a, b) => {
      return b.lastUpdatedAt.valueOf() - a.lastUpdatedAt.valueOf();
    });

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography id="cur-sv-coin-price" mt={6} variant="h4">
        {'Your Desired Canton Coin Price : '}
        {curSvCoinPriceVote?.coinPrice ? (
          <AmountDisplay amount={maybeBigNumber(curSvCoinPriceVote.coinPrice)!} currency="USD" />
        ) : (
          'Not Set'
        )}
      </Typography>
      <Typography mt={6} variant="h4">
        Desired Canton Coin Prices of Other Super Validators
      </Typography>
      <TableContainer>
        <Table style={{ tableLayout: 'fixed' }} className="sv-coin-price-table">
          <TableHead>
            <TableRow>
              <TableCell>Super Validator</TableCell>
              <TableCell>Desired CC Price</TableCell>
              <TableCell>Last Updated At</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {otherCoinPriceVotes.map(coinPriceVote => {
              return (
                <OtherCoinPricesRow
                  key={coinPriceVote.sv}
                  sv={coinPriceVote.sv}
                  coinPrice={maybeBigNumber(coinPriceVote.coinPrice)!}
                  lastUpdatedAt={coinPriceVote.lastUpdatedAt}
                />
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  );
};

interface OtherCoinPricesRowProps {
  sv: Party;
  coinPrice: BigNumber | undefined;
  lastUpdatedAt: Date;
}

const OtherCoinPricesRow: React.FC<OtherCoinPricesRowProps> = ({
  sv,
  coinPrice,
  lastUpdatedAt,
}) => {
  return (
    <TableRow className="coin-price-table-row">
      <TableCell>
        <PartyId partyId={sv} className="sv-coin-price" />
      </TableCell>
      <TableCell>
        {coinPrice ? <AmountDisplay amount={coinPrice} currency="USD" /> : 'Not Set'}
      </TableCell>
      <TableCell>
        <DateDisplay datetime={lastUpdatedAt.toISOString()} />
      </TableCell>
    </TableRow>
  );
};

const DesiredCoinPriceWithContexts: React.FC = () => {
  return (
    <SvClientProvider url={config.services.sv.url}>
      <DesiredCoinPrice />
    </SvClientProvider>
  );
};

export default DesiredCoinPriceWithContexts;
