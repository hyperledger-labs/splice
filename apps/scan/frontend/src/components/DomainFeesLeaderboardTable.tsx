import * as React from 'react';
import BigNumber from 'bignumber.js';
import { AmountDisplay, ErrorDisplay, Loading, TitledTable } from 'common-frontend';
import { useGetTopValidatorsByPurchasedTraffic } from 'common-frontend/scan-api';

import { Stack, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';

export const DomainFeesLeaderboardTable: React.FC = () => {
  const topValidatorsQuery = useGetTopValidatorsByPurchasedTraffic();

  switch (topValidatorsQuery.status) {
    case 'loading':
      return <Loading />;
    case 'error':
      return <ErrorDisplay message={'Could not retrieve validator leaderboard'} />;
    case 'success':
      const topValidators = topValidatorsQuery.data.validatorsByPurchasedTraffic.map(validator => ({
        name: validator.validator,
        numPurchases: validator.numPurchases,
        totalTrafficPurchased: validator.totalTrafficPurchased,
        totalCcSpent: BigNumber(validator.totalCcSpent),
        lastPurchasedInRound: validator.lastPurchasedInRound,
      }));

      return topValidators.length === 0 ? (
        <Stack spacing={0} alignItems={'center'} marginTop={3}>
          <Typography variant="h6" fontWeight="bold">
            No Validator Activity Yet
          </Typography>
        </Stack>
      ) : (
        <TitledTable title="Domain Fees Leaderboard">
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell align="right">Number of Purchases</TableCell>
              <TableCell align="right">Total Traffic Purchased</TableCell>
              <TableCell align="right">Total CC Spent</TableCell>
              <TableCell align="right">Last Purchased In Round</TableCell>
            </TableRow>
          </TableHead>

          <TableBody>
            {topValidators.map(validator => {
              return <ValidatorRow key={validator.name} validator={validator} />;
            })}
          </TableBody>
        </TitledTable>
      );
  }
};

export default DomainFeesLeaderboardTable;

const ValidatorRow: React.FC<{
  validator: {
    name: string;
    numPurchases: number;
    totalTrafficPurchased: number;
    totalCcSpent: BigNumber;
    lastPurchasedInRound: number;
  };
}> = ({ validator }) => {
  const { name, numPurchases, totalTrafficPurchased, totalCcSpent, lastPurchasedInRound } =
    validator;
  return (
    <TableRow className="domain-fees-leaderboard-row">
      <TableCell>{name}</TableCell>
      <TableCell align="right">{numPurchases}</TableCell>
      <TableCell align="right">{totalTrafficPurchased}</TableCell>
      <TableCell align="right">
        <AmountDisplay amount={totalCcSpent} currency="CC" />
      </TableCell>
      <TableCell align="right">{lastPurchasedInRound}</TableCell>
    </TableRow>
  );
};
