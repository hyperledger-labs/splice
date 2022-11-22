import { sameContracts, useInterval, Contract } from 'common-frontend';
import { Decimal } from 'decimal.js';
import { useCallback, useState } from 'react';

import {
  Button,
  FormGroup,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
} from '@mui/material';

import { Coin } from '@daml.js/canton-coin/lib/CC/Coin';

import { QuantityDisplay } from '../components/QuantityDisplay';
import { useWalletClient } from '../contexts/WalletServiceContext';

const Coins: React.FC = () => {
  const [coins, setCoins] = useState<Contract<Coin>[]>([]);
  const [tapValue, setTapValue] = useState<Decimal | undefined>(undefined);

  const { tap, list } = useWalletClient();

  const fetchCoins = useCallback(async () => {
    const newCoins = (await list()).coins;

    const decoded = newCoins.reduce((accumulator, c) => {
      const contractData = c.getContract();
      return contractData ? [...accumulator, Contract.decode(contractData, Coin)] : accumulator;
    }, [] as Contract<Coin>[]);

    setCoins(prev => (sameContracts(prev, decoded) ? prev : decoded));
  }, [list, setCoins]);

  useInterval(fetchCoins, 500);

  const copyToClipboard = async (text: string) => {
    await navigator.clipboard.writeText(text);
  };

  const parseTapValue = (value: string) => {
    try {
      setTapValue(new Decimal(value));
    } catch {
      setTapValue(undefined);
    }
  };

  const onTapClicked = () => {
    const decVal = tapValue as Decimal;
    const strVal = decVal.isInt() ? decVal.toFixed(1) : decVal.toString();
    tap(strVal);
  };

  return (
    <Stack spacing={2}>
      <FormGroup row>
        <TextField
          error={tapValue === undefined}
          label="Amount"
          onChange={event => parseTapValue(event.target.value)}
          id="tap-amount-field"
        ></TextField>
        <Button
          variant="contained"
          disabled={tapValue === undefined}
          onClick={onTapClicked}
          id="tap-button"
        >
          Tap
        </Button>
      </FormGroup>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Contract ID</TableCell>
            <TableCell>Initial Quantity</TableCell>
            <TableCell>Created At</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {coins.map(c => (
            <TableRow key={c.contractId} className="coins-table-row">
              <TableCell>
                <Button
                  variant="text"
                  sx={{ color: 'text.primary', fontWeight: 'regular' }}
                  onClick={() => copyToClipboard(c.contractId)}
                >
                  {c.contractId.slice(0, 10)}…
                </Button>
              </TableCell>
              <TableCell className="coins-table-quantity">
                <QuantityDisplay quantity={c.payload.quantity.initialQuantity} />
              </TableCell>
              <TableCell>{c.payload.quantity.createdAt.number}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Stack>
  );
};

export default Coins;
