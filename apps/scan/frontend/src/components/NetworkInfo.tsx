// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  DateDisplay,
  ErrorDisplay,
  getAmuletConfigurationAsOfNow,
  Loading,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { microsecondsToMinutes } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import {
  useGetAmuletRules,
  useOpenRounds,
} from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import BigNumber from 'bignumber.js';
import { formatDistanceToNow } from 'date-fns';

import {
  Card,
  CardContent,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';

import { AmuletConfig } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig/module';
import { SteppedRate } from '@daml.js/splice-amulet/lib/Splice/Fees/module';

import { useScanConfig } from '../utils';

const NetworkInfo: React.FC = () => {
  const config = useScanConfig();
  const amuletName = config.spliceInstanceNames.amuletName;
  const getAmuletRulesQuery = useGetAmuletRules();
  const openRoundsQuery = useOpenRounds();

  let openRoundsDisplay: JSX.Element;
  switch (openRoundsQuery.status) {
    case 'loading':
      openRoundsDisplay = <Loading />;
      break;
    case 'error':
      openRoundsDisplay = <ErrorDisplay message="Failed to fetch open rounds" />;
      break;
    case 'success':
      const sortedRounds = openRoundsQuery.data.sort(
        (a, b) => parseInt(a.payload.round.number) - parseInt(b.payload.round.number)
      );
      openRoundsDisplay = (
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Round</TableCell>
              <TableCell>Opens At</TableCell>
              <TableCell>Target Closes At</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {sortedRounds.map(round => {
              const {
                round: { number },
                opensAt,
                targetClosesAt,
              } = round.payload;
              return (
                <TableRow key={number} className="open-mining-round-row">
                  <TableCell className="round-number">{number}</TableCell>
                  <TableCell className="round-opens-at">
                    <DateDisplay datetime={opensAt} />
                  </TableCell>
                  <TableCell className="round-target-closes-at">
                    <DateDisplay datetime={targetClosesAt} />
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      );
  }

  const configDescription = `The ${amuletName} configuration details below are voted on by the Super Validators, and may be updated over time.`;
  const feesDescription =
    `Fees burn ${amuletName}. Fees encourage active use of ${amuletName} and maintain positive pressure on the value of ${amuletName} by constraining the total supply over time.` +
    `The Super Validators mint ${amuletName} via smart contracts triggered by a consensus vote of 2/3 of the Super Validators.` +
    `Super Validators and Validators burn ${amuletName} to pay fees. Minting and burning takes place in fixed time cycles called rounds.`;

  switch (getAmuletRulesQuery.status) {
    case 'loading':
      return <Loading />;
    case 'error':
      return <ErrorDisplay message="Failed to fetch amulet rules" />;
    case 'success':
      return (
        <Card>
          <CardContent>
            <Stack spacing={4}>
              <Typography variant="h3">{`Current ${amuletName} Configuration`}</Typography>
              <Typography variant="body1">{configDescription}</Typography>
              <Stack spacing={1}>
                <Typography variant="h3">Open Rounds</Typography>
                {openRoundsDisplay}
              </Stack>
              <Stack spacing={1}>
                <Typography variant="h3">Fees</Typography>
                <Typography variant="body1">{feesDescription}</Typography>
              </Stack>
              <FeesTable
                amuletConfig={
                  getAmuletConfigurationAsOfNow(
                    getAmuletRulesQuery.data.contract.payload.configSchedule
                  ).initialValue
                }
              />
              <NextConfigUpdate />
            </Stack>
          </CardContent>
        </Card>
      );
  }
};

const NextConfigUpdate: React.FC = () => {
  const { data: amuletRules } = useGetAmuletRules();

  const futureValues =
    amuletRules &&
    getAmuletConfigurationAsOfNow(amuletRules.contract.payload.configSchedule).futureValues;
  const configurationUpdate =
    futureValues && futureValues.length > 0 && new Date(futureValues[0]._1);

  return (
    <Stack spacing={2}>
      <Typography variant="h3">Next Configuration Update</Typography>
      {configurationUpdate ? (
        <Stack spacing={4}>
          <Typography variant="body1" id="next-config-update-time">
            {formatDistanceToNow(configurationUpdate, { includeSeconds: true })}
          </Typography>
          <Typography variant="h3" id="next-config-update">
            Fees
          </Typography>
          <FeesTable amuletConfig={futureValues.at(0)!._2} />
        </Stack>
      ) : (
        <Typography variant="caption" id="next-config-update-time">
          No currently scheduled configuration changes
        </Typography>
      )}
    </Stack>
  );
};

const FeesTable: React.FC<{ amuletConfig: AmuletConfig<'USD'> }> = ({ amuletConfig }) => {
  const config = useScanConfig();
  const amuletName = config.spliceInstanceNames.amuletName;
  return (
    <TableContainer>
      <Table>
        <TableBody>
          <FeeTableRow
            name="Base Transfer Fee"
            value={`${BigNumber(amuletConfig.transferConfig.createFee.fee)} USD`}
            description="A fixed fee for each transfer."
          />
          <TransferFees transferFees={amuletConfig.transferConfig.transferFee} />
          <FeeTableRow
            name="Round Tick Duration"
            value={`${microsecondsToMinutes(amuletConfig.tickDuration.microseconds)} Minutes`}
            description="The interval at which new rounds are opened."
          />
          <FeeTableRow
            name="Synchronizer Fee"
            value={`${BigNumber(
              amuletConfig.decentralizedSynchronizer.fees.extraTrafficPrice
            )} $/MB`}
            description="Cost of processing 1 MB of transactions through the Global Synchronizer"
          />
          <FeeTableRow
            name="Holding Fee"
            value={`${BigNumber(amuletConfig.transferConfig.holdingFee.rate)} USD/Round`}
            description={`A fixed fee for maintaining each active ${amuletName} record, charged per round.`}
          />
          <FeeTableRow
            name="Lock Holder Fee"
            value={`${BigNumber(amuletConfig.transferConfig.lockHolderFee.fee)} USD`}
            description={`A fixed fee for extra lock holders on ${amuletName} records.`}
          />
        </TableBody>
      </Table>
    </TableContainer>
  );
};

const FeeTableRow: React.FC<{ name: string; description: string; value: string }> = ({
  name,
  description,
  value,
}) => {
  return (
    <TableRow className="fee-table-row">
      <TableCell>
        <Typography variant="body1" fontWeight="bold" textTransform="uppercase">
          {name}
        </Typography>
        <Typography variant="caption">{description}</Typography>
      </TableCell>
      <TableCell align="right">
        <Typography
          variant="h6"
          fontWeight="bold"
          id={name.toLocaleLowerCase().replaceAll(' ', '-')}
        >
          {value}
        </Typography>
      </TableCell>
    </TableRow>
  );
};

const toPercentFmt = (rate: string): string => `${BigNumber(rate).multipliedBy(100)}%`;

const TransferFees: React.FC<{ transferFees: SteppedRate }> = ({ transferFees }) => {
  const config = useScanConfig();
  const amuletName = config.spliceInstanceNames.amuletName;
  const transferFeeSteps = transferFees.steps.reduce<
    { fee: string; range: string; last: boolean }[]
  >(
    (acc, current, index, array) => {
      const nextStep = array[index + 1];
      if (nextStep !== undefined) {
        return [
          ...acc,
          {
            fee: toPercentFmt(current._2),
            range: `${BigNumber(current._1)} - ${BigNumber(nextStep._1)} USD`,
            last: false,
          },
        ];
      } else {
        return [
          ...acc,
          {
            fee: toPercentFmt(current._2),
            range: `> ${BigNumber(current._1)} USD`,
            last: true,
          },
        ];
      }
    },
    [
      {
        fee: toPercentFmt(transferFees.initialRate),
        range: `< ${BigNumber(transferFees.steps[0]._1)} USD`,
        last: false,
      },
    ]
  );

  return (
    <TableRow>
      <TableCell>
        <Typography variant="body1" fontWeight="bold" textTransform="uppercase">
          Transfer Fee
        </Typography>
        <Typography variant="caption">{`A proportional fee charged for the value created by locking and/or transferring a
          particular amount of ${amuletName}. The rates are specified in tranches.`}</Typography>
      </TableCell>
      <TableCell align="right">
        <TableContainer>
          <Table>
            <TableBody>
              {transferFeeSteps.map(step => (
                <TransferFeeRow key={step.range} {...step} />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </TableCell>
    </TableRow>
  );
};

const TransferFeeRow: React.FC<{ range: string; fee: string; last?: boolean }> = ({
  range,
  fee,
  last,
}) => {
  return (
    <TableRow className="transfer-fee-row">
      <TableCell sx={last ? { borderBottom: 'none' } : undefined}>
        <Typography variant="body1">{range}</Typography>
      </TableCell>
      <TableCell align="right" sx={{ borderBottom: 'none' }}>
        <Typography variant="h6" fontWeight="bold">
          {fee}
        </Typography>
      </TableCell>
    </TableRow>
  );
};

export default NetworkInfo;
