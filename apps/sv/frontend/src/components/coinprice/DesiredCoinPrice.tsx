import { useMutation } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import { AmountDisplay, DateDisplay, Loading, PartyId, SvClientProvider } from 'common-frontend';
import React, { useCallback, useState } from 'react';

import EditIcon from '@mui/icons-material/Edit';
import {
  IconButton,
  Stack,
  Table,
  TableContainer,
  TableHead,
  TableBody,
  TableCell,
  TableRow,
  Typography,
  TextField,
  Button,
} from '@mui/material';

import { Numeric, Optional, Party } from '@daml/types';

import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useSvcInfos } from '../../contexts/SvContext';
import { useCoinPriceVotes } from '../../hooks/useCoinPriceVotes';
import { CoinPriceVote } from '../../models/models';
import { config } from '../../utils';

const DesiredCoinPrice: React.FC = () => {
  const coinPriceVotesQuery = useCoinPriceVotes();
  const { updateDesiredCoinPrice } = useSvAdminClient();
  const [curPrice, setCurPrice] = useState<BigNumber>(new BigNumber(0.0));
  const [enableEdit, setEnableEdit] = useState<boolean>(false);
  const updateDesiredCoinPriceMutation = useMutation({
    mutationFn: () => {
      return updateDesiredCoinPrice(curPrice);
    },
    onSettled: async () => {
      setEnableEdit(false);
    },
  });

  const maybeBigNumber = (maybeNumeric: Optional<Numeric>) => {
    return maybeNumeric !== null ? new BigNumber(maybeNumeric) : undefined;
  };

  const svcInfosQuery = useSvcInfos();
  const getMemberName = useCallback(
    (partyId: string) => {
      const member = svcInfosQuery.data?.svcRules.payload.members.get(partyId);
      return member ? member.name : '';
    },
    [svcInfosQuery.data]
  );

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

  const otherCoinPriceVotes = coinPriceVotesQuery.data
    .filter(v => v.sv !== svPartyId)
    .sort((a, b) => {
      return b.lastUpdatedAt.valueOf() - a.lastUpdatedAt.valueOf();
    });

  const isInvalidPrice = curPrice.lte(0.0);
  return (
    <>
      <Typography mt={6} variant="h4">
        {'Your Desired Canton Coin Price'}
      </Typography>
      {enableEdit ? (
        updateDesiredCoinPriceMutation.isLoading ? (
          <Loading />
        ) : (
          <Stack direction="row">
            <TextField
              error={isInvalidPrice}
              label="Amount"
              onChange={event => setCurPrice(new BigNumber(event.target.value))}
              value={curPrice}
              type="number"
              id="desired-coin-price-field"
            />
            <Button
              variant="contained"
              disabled={isInvalidPrice}
              onClick={() => updateDesiredCoinPriceMutation.mutate()}
              id="update-coin-price-button"
            >
              Update
            </Button>
            <Button
              variant="contained"
              color="warning"
              disabled={false}
              onClick={() => setEnableEdit(false)}
              id="cancel-coin-price-button"
            >
              Cancel
            </Button>
          </Stack>
        )
      ) : (
        <Typography id="cur-sv-coin-price-usd" variant="h6">
          {curSvCoinPriceVote?.coinPrice ? (
            <AmountDisplay amount={maybeBigNumber(curSvCoinPriceVote.coinPrice)!} currency="USD" />
          ) : (
            'Not Set'
          )}
          <IconButton
            onClick={() => {
              // set initial value for editing
              setCurPrice(
                curSvCoinPriceVote?.coinPrice
                  ? maybeBigNumber(curSvCoinPriceVote.coinPrice)!
                  : // TODO(#4632) If desired price is not yet set in this SV
                    // Current median value would be a better choice than 0.0 as a initial value for editing.
                    // but it would be easier to do it after #4632 is done.
                    new BigNumber(0.0)
              );
              setEnableEdit(true);
            }}
          >
            <EditIcon id="edit-coin-price-button" fontSize={'small'} />
          </IconButton>
        </Typography>
      )}

      <Typography mt={6} variant="h4">
        Desired Canton Coin Prices of Other Super Validators
      </Typography>
      <TableContainer>
        <Table style={{ tableLayout: 'fixed' }} className="sv-coin-price-table">
          <TableHead>
            <TableRow>
              <TableCell>Super Validator</TableCell>
              <TableCell>Super Validator Party ID</TableCell>
              <TableCell>Desired Coin Price</TableCell>
              <TableCell>Last Updated At</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {otherCoinPriceVotes.map(coinPriceVote => {
              return (
                <OtherCoinPricesRow
                  key={coinPriceVote.sv}
                  sv={coinPriceVote.sv}
                  svName={getMemberName(coinPriceVote.sv)}
                  coinPrice={maybeBigNumber(coinPriceVote.coinPrice)!}
                  lastUpdatedAt={coinPriceVote.lastUpdatedAt}
                />
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </>
  );
};

interface OtherCoinPricesRowProps {
  svName: string;
  sv: Party;
  coinPrice: BigNumber | undefined;
  lastUpdatedAt: Date;
}

const OtherCoinPricesRow: React.FC<OtherCoinPricesRowProps> = ({
  svName,
  sv,
  coinPrice,
  lastUpdatedAt,
}) => {
  return (
    <TableRow className="coin-price-table-row">
      <TableCell>{svName}</TableCell>
      <TableCell>
        <PartyId partyId={sv} className="sv-party" />
      </TableCell>
      <TableCell className="coin-price">
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
