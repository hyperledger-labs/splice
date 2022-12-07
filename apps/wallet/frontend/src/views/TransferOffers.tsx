import { useInterval, Contract, DirectoryEntry } from 'common-frontend';
import { Decimal } from 'decimal.js';
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

import { AcceptedTransferOffer, TransferOffer } from '@daml.js/wallet/lib/CN/Wallet/TransferOffer';

import { PaymentQuantityDisplay } from '../components/QuantityDisplay';
import Timestamp from '../components/Timestamp';
import { useUserState } from '../contexts/UserContext';
import { useWalletClient } from '../contexts/WalletServiceContext';

// TODO(M3-02): directory integration for the receiver in a new offer

const TransferOffers: React.FC = () => {
  const {
    createTransferOffer,
    listTransferOffers,
    acceptTransferOffer,
    rejectTransferOffer,
    withdrawTransferOffer,
    listAcceptedTransferOffers,
  } = useWalletClient();
  const { primaryPartyId } = useUserState();

  const [offers, setOffers] = useState<Contract<TransferOffer>[]>([]);
  const fetchTransferOffers = useCallback(async () => {
    const { offersList } = await listTransferOffers();
    setOffers(offersList);
  }, [listTransferOffers, setOffers]);

  useInterval(fetchTransferOffers, 500);

  const [acceptedOffers, setAcceptedOffers] = useState<Contract<AcceptedTransferOffer>[]>([]);
  const fetchAcceptedTransferOffers = useCallback(async () => {
    const { acceptedOffersList } = await listAcceptedTransferOffers();
    setAcceptedOffers(acceptedOffersList);
  }, [listAcceptedTransferOffers, setAcceptedOffers]);

  useInterval(fetchAcceptedTransferOffers, 500);

  const [receiver, setReceiver] = useState<string>('');
  const [transferQuantity, setTransferQuantity] = useState<Decimal>(new Decimal(0.0));
  const [description, setDescription] = useState('');
  const createOffer = async (ev: React.FormEvent<HTMLFormElement>) => {
    ev.preventDefault();
    const now = new Date();
    const expires = new Date(now.setMinutes(now.getMinutes() + 2));
    // TODO(#1776): Expiration is currently hard-coded to 2 minutes from now - add a UI for controlling that
    await createTransferOffer(receiver, transferQuantity, description, expires);
    setReceiver('');
  };

  return (
    <Stack spacing={2}>
      <form onSubmit={createOffer}>
        <Stack direction="row">
          <TextField
            id="create-offer-receiver"
            label="Receiver"
            value={receiver}
            onChange={event => setReceiver(event.target.value)}
          ></TextField>
          <TextField
            id="create-offer-quantity"
            label="Amount"
            value={transferQuantity}
            type="number"
            error={transferQuantity.lessThanOrEqualTo(0.0)}
            onChange={event => setTransferQuantity(new Decimal(event.target.value))}
          ></TextField>
          <TextField
            id={'create-offer-description'}
            label="Description"
            value={description}
            onChange={event => setDescription(event.target.value)}
          ></TextField>
          <Button
            variant="contained"
            type="submit"
            id="create-offer-button"
            disabled={transferQuantity.lessThanOrEqualTo(0.0)}
          >
            Create a Transfer Offer
          </Button>
        </Stack>
      </form>
      <Typography variant="h4">Active Transfer Offers</Typography>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Sender</TableCell>
            <TableCell>Receiver</TableCell>
            <TableCell>Amount</TableCell>
            <TableCell>Description</TableCell>
            <TableCell>Expiration</TableCell>
            <TableCell>Actions</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {offers.map(c => (
            <TableRow key={c.contractId} className="transfer-offers-row">
              <TableCell className="transfer-offers-table-sender">
                <DirectoryEntry partyId={c.payload.sender} />
              </TableCell>
              <TableCell className="transfer-offers-table-receiver">
                <DirectoryEntry partyId={c.payload.receiver} />
              </TableCell>
              <TableCell className="transfer-offers-table-quantity">
                <PaymentQuantityDisplay quantity={c.payload.quantity} />
              </TableCell>
              <TableCell className="transfer-offers-table-description">
                {c.payload.description}
              </TableCell>
              <TableCell className="transfer-offers-table-expiration">
                <Timestamp time={c.payload.expiresAt} />
              </TableCell>
              <TableCell>
                {c.payload.receiver === primaryPartyId && (
                  <Button
                    onClick={() => acceptTransferOffer(c.contractId)}
                    className="transfer-offers-table-accept"
                  >
                    Accept
                  </Button>
                )}
                {c.payload.receiver === primaryPartyId && (
                  <Button
                    onClick={() => rejectTransferOffer(c.contractId)}
                    className="transfer-offers-table-reject"
                  >
                    Reject
                  </Button>
                )}
                {c.payload.sender === primaryPartyId && (
                  <Button
                    onClick={() => withdrawTransferOffer(c.contractId)}
                    className="transfer-offers-table-withdraw"
                  >
                    Withdraw
                  </Button>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <Typography variant="h4">Accepted Transfer Offers</Typography>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Sender</TableCell>
            <TableCell>Receiver</TableCell>
            <TableCell>Amount</TableCell>
            <TableCell>Description</TableCell>
            <TableCell>Expiration</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {acceptedOffers.map(c => (
            <TableRow key={c.contractId} className="accepted-transfer-offers-row">
              <TableCell>
                <DirectoryEntry partyId={c.payload.sender} />
              </TableCell>
              <TableCell>
                <DirectoryEntry partyId={c.payload.receiver} />
              </TableCell>
              <TableCell>
                <PaymentQuantityDisplay quantity={c.payload.quantity} />
              </TableCell>
              <TableCell>{c.payload.description}</TableCell>
              <TableCell>
                <Timestamp time={c.payload.expiresAt} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Stack>
  );
};

export default TransferOffers;
