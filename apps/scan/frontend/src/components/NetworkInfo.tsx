import * as React from 'react';
import BigNumber from 'bignumber.js';
import {
  ErrorDisplay,
  getCoinConfigurationAsOfNow,
  Loading,
  microsecondsToMinutes,
} from 'common-frontend';
import { CoinConfig } from 'common-frontend/daml.js/canton-coin-0.1.0/lib/CC/CoinConfig/module';
import { SteppedRate } from 'common-frontend/daml.js/canton-coin-0.1.0/lib/CC/Fees/module';
import { useGetCoinRules } from 'common-frontend/scan-api';
import { formatDistanceToNow } from 'date-fns';

import {
  Card,
  CardContent,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableRow,
  Typography,
} from '@mui/material';

const NetworkInfo: React.FC = () => {
  const getCoinRulesQuery = useGetCoinRules();

  switch (getCoinRulesQuery.status) {
    case 'loading':
      return <Loading />;
    case 'error':
      return <ErrorDisplay message="Failed to fetch coin rules" />;
    case 'success':
      return (
        <Card>
          <CardContent>
            <Stack spacing={4}>
              <Typography variant="h3">Current Canton Coin Configuration</Typography>
              <Typography variant="body1">
                The coin configuration details below are voted on by the SVC, and may be updated
                over time.
              </Typography>
              <Stack spacing={1}>
                <Typography variant="h3">Fees</Typography>
                <Typography variant="body1">
                  Fees exist because of x and z. Here are important things you should know...
                </Typography>
              </Stack>
              <FeesTable
                coinConfig={
                  getCoinConfigurationAsOfNow(getCoinRulesQuery.data.payload.configSchedule)
                    .initialValue
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
  const { data: coinRules } = useGetCoinRules();

  const futureValues =
    coinRules && getCoinConfigurationAsOfNow(coinRules.payload.configSchedule).futureValues;
  const configurationUpdate =
    futureValues && futureValues.length > 0 && new Date(futureValues[0]._1);

  return (
    <Stack spacing={2}>
      <Typography variant="h3">Next Configuration Update</Typography>
      <Typography variant="body1" id="next-config-update">
        {configurationUpdate ? (
          formatDistanceToNow(configurationUpdate, { includeSeconds: true })
        ) : (
          <Typography variant="caption">No currently scheduled configuration changes</Typography>
        )}
      </Typography>
    </Stack>
  );
};

const FeesTable: React.FC<{ coinConfig: CoinConfig<'USD'> }> = ({ coinConfig }) => {
  const feesDescription = 'This fee is charged when x and y.';
  return (
    <TableContainer>
      <Table>
        <TableBody>
          <FeeTableRow
            name="Coin Creation Fee"
            value={`${BigNumber(coinConfig.transferConfig.createFee.fee)} CC`}
            description={feesDescription}
          />
          <FeeTableRow
            name="Holding Fee"
            value={`${BigNumber(coinConfig.transferConfig.holdingFee.rate)} CC/Round`}
            description={feesDescription}
          />
          <FeeTableRow
            name="Lock Holder Fee"
            value={`${BigNumber(coinConfig.transferConfig.lockHolderFee.fee)} CC`}
            description={feesDescription}
          />
          <TransferFees transferFees={coinConfig.transferConfig.transferFee} />
          <FeeTableRow
            name="Round Tick Duration"
            value={`${microsecondsToMinutes(coinConfig.tickDuration.microseconds)} Minutes`}
            description={feesDescription}
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
            range: `${BigNumber(current._1)} - ${BigNumber(nextStep._1)} CC`,
            last: false,
          },
        ];
      } else {
        return [
          ...acc,
          {
            fee: toPercentFmt(current._2),
            range: `> ${BigNumber(current._1)} CC`,
            last: true,
          },
        ];
      }
    },
    [
      {
        fee: toPercentFmt(transferFees.initialRate),
        range: `< ${BigNumber(transferFees.steps[0]._1)} CC`,
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
        <Typography variant="caption">This fee is charged when x and y.</Typography>
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
