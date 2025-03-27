// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  ErrorDisplay,
  Loading,
  PartyId,
  TitledTable,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { useGetTopValidatorsByValidatorFaucets } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';

import { Stack, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';

export const ValidatorFaucetsTable: React.FC = () => {
  const topValidatorsQuery = useGetTopValidatorsByValidatorFaucets();

  switch (topValidatorsQuery.status) {
    case 'loading':
      return <Loading />;
    case 'error':
      return <ErrorDisplay message={'Could not retrieve validator liveness leaderboard'} />;
    case 'success':
      const topValidators = topValidatorsQuery.data.validatorsByReceivedFaucets.map(validator => ({
        partyId: validator.validator,
        numRoundsCollected: validator.numRoundsCollected,
        numRoundsMissed: validator.numRoundsMissed,
        firstCollectedInRound: validator.firstCollectedInRound,
        lastCollectedInRound: validator.lastCollectedInRound,
      }));

      return topValidators.length === 0 ? (
        <Stack spacing={0} alignItems={'center'} marginTop={3}>
          <Typography variant="h6" fontWeight="bold">
            No Validator Activity Yet
          </Typography>
        </Stack>
      ) : (
        <TitledTable title="Validator Liveness Leaderboard">
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell align="right">Rounds Collected</TableCell>
              <TableCell align="right">Rounds Missed</TableCell>
              <TableCell align="right">First Collected In Round</TableCell>
              <TableCell align="right">Last Collected In Round</TableCell>
            </TableRow>
          </TableHead>

          <TableBody>
            {topValidators.map(validator => {
              return <ValidatorRow key={validator.partyId} validator={validator} />;
            })}
          </TableBody>
        </TitledTable>
      );
  }
};

export default ValidatorFaucetsTable;

const ValidatorRow: React.FC<{
  validator: {
    partyId: string;
    numRoundsCollected: number;
    numRoundsMissed: number;
    firstCollectedInRound: number;
    lastCollectedInRound: number;
  };
}> = ({ validator }) => {
  const {
    partyId,
    numRoundsCollected,
    numRoundsMissed,
    firstCollectedInRound,
    lastCollectedInRound,
  } = validator;
  return (
    <TableRow
      className="validator-faucets-leaderboard-row"
      data-selenium-text={`${partyId} ${numRoundsCollected} ${numRoundsMissed} ${firstCollectedInRound} ${lastCollectedInRound}`}
    >
      <TableCell>
        <PartyId partyId={partyId} />
      </TableCell>
      <TableCell align="right">{numRoundsCollected}</TableCell>
      <TableCell align="right">{numRoundsMissed}</TableCell>
      <TableCell align="right">{firstCollectedInRound}</TableCell>
      <TableCell align="right">{lastCollectedInRound}</TableCell>
    </TableRow>
  );
};
