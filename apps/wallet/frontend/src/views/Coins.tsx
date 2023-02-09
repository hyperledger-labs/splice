import { sameContracts, useInterval, Contract } from 'common-frontend';
import { Decimal } from 'decimal.js';
import { useCallback, useState } from 'react';

import {
  Button,
  CardContent,
  FormGroup,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';

import { Coin } from '@daml.js/canton-coin/lib/CC/Coin';

import { AmountDisplay } from '../components/AmountDisplay';
import { GetBalanceResponse, useWalletClient } from '../contexts/WalletServiceContext';

const Coins: React.FC = () => {
  const initialBalance = {
    effectiveLockedQty: '',
    effectiveUnlockedQty: '',
    round: 0,
    totalHoldingFees: '',
  };
  const [coins, setCoins] = useState<Contract<Coin>[]>([]);
  const [tapValue, setTapValue] = useState<Decimal>(new Decimal(0.0));
  const [balance, setBalance] = useState<GetBalanceResponse>(initialBalance);

  const { tap, list, getBalance } = useWalletClient();

  const fetchCoins = useCallback(async () => {
    const newCoins = (await list()).coins;

    const decoded = newCoins.reduce((accumulator, c) => {
      const contractData = c.contract;
      return contractData
        ? [...accumulator, Contract.decodeOpenAPI(contractData, Coin)]
        : accumulator;
    }, [] as Contract<Coin>[]);

    setCoins(prev => (sameContracts(prev, decoded) ? prev : decoded));
  }, [list, setCoins]);

  const fetchBalance = useCallback(async () => {
    const balance = await getBalance();
    setBalance(balance);
  }, [getBalance, setBalance]);

  useInterval(fetchCoins, 500);
  useInterval(fetchBalance, 500);

  const copyToClipboard = async (text: string) => {
    await navigator.clipboard.writeText(text);
  };

  const onTapClicked = () => {
    const decVal = tapValue as Decimal;
    const strVal = decVal.isInt() ? decVal.toFixed(1) : decVal.toString();
    tap(strVal);
  };

  const formatBalanceValue = (value: string) => {
    return value === '' ? 'Loading...' : Number(value).toFixed(10);
  };

  // Check balance first, then tap, then check updated balance.
  return (
    <Stack spacing={2}>
      <FormGroup row>
        <TextField
          error={tapValue.lessThanOrEqualTo(0.0)}
          label="Amount"
          onChange={event => setTapValue(new Decimal(event.target.value))}
          type="number"
          id="tap-amount-field"
        ></TextField>
        <Button
          variant="contained"
          disabled={tapValue.lessThanOrEqualTo(0.0)}
          onClick={onTapClicked}
          id="tap-button"
        >
          Tap
        </Button>
      </FormGroup>
      <Stack direction={'row'} spacing={2}>
        <CardContent>
          <Typography variant={'h6'}>
            <span id={'unlocked-qty'}>{formatBalanceValue(balance.effectiveUnlockedQty)}</span> CC
          </Typography>
          <Typography variant={'caption'} gutterBottom>
            Unlocked Coins
          </Typography>
        </CardContent>
        <CardContent>
          <Typography variant={'h6'}>
            <span id={'locked-qty'}>{formatBalanceValue(balance.effectiveLockedQty)}</span> CC
          </Typography>
          <Typography variant={'caption'} gutterBottom>
            Locked Coins
          </Typography>
        </CardContent>
        <CardContent>
          <Typography variant={'h6'}>
            <span id={'holding-fees'}>{formatBalanceValue(balance.totalHoldingFees)}</span> CC
          </Typography>
          <Typography variant={'caption'} gutterBottom>
            Holding Fees
          </Typography>
        </CardContent>
      </Stack>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Contract ID</TableCell>
            <TableCell>Initial Amount</TableCell>
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
              <TableCell className="coins-table-amount">
                <AmountDisplay amount={c.payload.amount.initialAmount} />
              </TableCell>
              <TableCell>{c.payload.amount.createdAt.number}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Stack>
  );
};

export default Coins;
