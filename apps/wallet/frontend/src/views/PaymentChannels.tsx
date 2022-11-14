import { useInterval, Contract } from 'common-frontend';
import React, { useCallback, useState } from 'react';

import {
  Button,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';

import { PaymentChannelProposal } from '@daml.js/wallet/lib/CN/Wallet/PaymentChannel';

import { useWalletClient } from '../contexts/WalletServiceContext';

const PaymentChannels: React.FC = () => {
  const {
    executeDirectTransfer,
    proposePaymentChannel,
    listPaymentChannelProposals,
    acceptPaymentChannelProposal,
  } = useWalletClient();

  const [proposals, setProposals] = useState<Contract<PaymentChannelProposal>[]>([]);
  const fetchChannelProposals = useCallback(async () => {
    const { proposalsList } = await listPaymentChannelProposals();
    setProposals(proposalsList);
  }, [listPaymentChannelProposals, setProposals]);

  useInterval(fetchChannelProposals, 500);

  const [receiver, setReceiver] = useState<string>('');
  const proposeChannel = async (ev: React.FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    await proposePaymentChannel(receiver, '0.5', true, true, true);
    setReceiver('');
  };

  const [transferReceiverPartyId, setTransferReceiverPartyId] = useState('');
  const [transferQuantity, setTransferQuantity] = useState('');

  const directTransfer = async (ev: React.FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    await executeDirectTransfer(transferQuantity, transferReceiverPartyId);
  };

  return (
    <Stack spacing={2}>
      <form onSubmit={directTransfer}>
        <Stack direction="row">
          <TextField
            label="Receiver"
            value={transferReceiverPartyId}
            onChange={event => setTransferReceiverPartyId(event.target.value)}
          ></TextField>
          <TextField
            label="Amount"
            value={transferQuantity}
            onChange={event => setTransferQuantity(event.target.value)}
          ></TextField>
          <Button variant="contained" type="submit">
            Transfer over channel
          </Button>
        </Stack>
      </form>
      <form onSubmit={proposeChannel}>
        <Stack direction="row">
          <TextField
            label="Receiver"
            value={receiver}
            onChange={event => setReceiver(event.target.value)}
          ></TextField>
          <Button variant="contained" type="submit">
            Propose channel
          </Button>
        </Stack>
      </form>
      <Typography variant="h4">Channel proposals</Typography>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Proposal from</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {proposals.map(c => (
            <TableRow key={c.contractId}>
              <TableCell>{c.payload.proposer}</TableCell>
              <TableCell>
                <Button onClick={() => acceptPaymentChannelProposal(c.contractId)}>Approve</Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Stack>
  );
};

export default PaymentChannels;
