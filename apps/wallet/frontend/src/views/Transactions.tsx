import React from 'react';

import { ArrowBackRounded, ArrowForwardRounded } from '@mui/icons-material';
import {
  Button,
  Icon,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';

import { AmountDisplay } from '../components/AmountDisplay';

const Transactions: React.FC = () => {
  return (
    <>
      <Typography variant="h5">Transaction History (mock)</Typography>
      <Stack direction="column" spacing={4}>
        <TableContainer>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell></TableCell>
                <TableCell></TableCell>
                <TableCell></TableCell>
              </TableRow>
            </TableHead>

            <TableBody>
              <TableRow key={1} className="transactions-table-row">
                <TableCell>
                  <Icon fontSize="small">
                    <ArrowBackRounded fontSize="small" />
                  </Icon>{' '}
                  Sent
                </TableCell>
                <TableCell>
                  <Stack direction="column">
                    <Typography>Thomas Edison</Typography>
                    <Typography variant="caption">via Splitwell</Typography>
                  </Stack>
                </TableCell>
                <TableCell>
                  <Stack direction="column">
                    <Typography>
                      <AmountDisplay amount="-31" />
                    </Typography>
                    <Typography variant="caption">
                      <AmountDisplay amount="372" currency="USD" /> @{' '}
                      <AmountDisplay amount="12" currency="CC/USD" />
                    </Typography>
                  </Stack>
                </TableCell>
              </TableRow>
              <TableRow key={2} className="transactions-table-row">
                <TableCell>
                  <Icon fontSize="small">
                    <ArrowForwardRounded fontSize="small" />
                  </Icon>{' '}
                  Received
                </TableCell>
                <TableCell>
                  <Stack direction="column">
                    <Typography>Multiple Recipients</Typography>
                    <Typography variant="caption"> via BORF</Typography>
                  </Stack>
                </TableCell>
                <TableCell>
                  <Stack direction="column">
                    <Typography>
                      <AmountDisplay amount="+93" />
                    </Typography>
                    <Typography variant="caption">
                      <AmountDisplay amount="1,116" currency="USD" /> @{' '}
                      <AmountDisplay amount="12" currency="CC/USD" />
                    </Typography>
                  </Stack>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </TableContainer>
        <Button variant="outlined">Load more...</Button>
      </Stack>
    </>
  );
};

export default Transactions;
