import * as React from 'react';
import addDays from 'date-fns/addDays';
import formatRelative from 'date-fns/formatRelative';

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
  const configurationUpdate = addDays(new Date(), 1);
  return (
    <Card>
      <CardContent>
        <Stack spacing={4}>
          <Typography variant="h3">Current Canton Coin Configuration</Typography>
          <Typography variant="body1">
            The details below fluctuate based on x and y.
            <br />
            Refresh for most up to date information.
          </Typography>
          <Stack spacing={1}>
            <Typography variant="h3">Fees</Typography>
            <Typography variant="body1">
              Fees exist because of x and z. Here are important things you should know...
            </Typography>
          </Stack>
          <FeesTable />
          <Stack spacing={2}>
            <Typography variant="h3">Next Configuration Update</Typography>
            <Typography variant="body1">
              {formatRelative(configurationUpdate, new Date())}
            </Typography>
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  );
};

const FeesTable: React.FC = () => {
  const feesDescription = 'This fee is charged when x and y.';
  return (
    <TableContainer>
      <Table>
        <TableBody>
          <FeeTableRow name="Coin Creation Fee" value="0.01 CC" description={feesDescription} />
          <FeeTableRow name="Holding Fee" value="0.002 CC/Round" description={feesDescription} />
          <FeeTableRow name="Lock Holder Fee" value="0.03 CC" description={feesDescription} />
          <TransferFees />
          <FeeTableRow
            name="Round Tick Duration"
            value="2.5 Minutes"
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
    <TableRow>
      <TableCell>
        <Typography variant="body1" fontWeight="bold" textTransform="uppercase">
          {name}
        </Typography>
        <Typography variant="caption">{description}</Typography>
      </TableCell>
      <TableCell align="right">
        <Typography variant="h6" fontWeight="bold">
          {value}
        </Typography>
      </TableCell>
    </TableRow>
  );
};

const TransferFees = () => {
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
              <TransferFeeRow range="< 100 CC" fee="1%" />
              <TransferFeeRow range="100-1000 CC" fee="0.5%" />
              <TransferFeeRow range="> 1000 CC" fee="0.25%" last />
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
    <TableRow>
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
