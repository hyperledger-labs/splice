import {
  Contract,
  DirectoryEntry as DirectoryEntryComponent,
  DirectoryField,
  useInterval,
  useUserState,
} from 'common-frontend';
import { Decimal } from 'decimal.js';
import React, { useCallback, useState } from 'react';
import { v4 as uuidv4 } from 'uuid';

import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  MenuItem,
  Select,
  SelectChangeEvent,
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
import { Party } from '@daml/types';

import { PaymentAmountDisplay } from '../components/AmountDisplay';
import Timestamp from '../components/Timestamp';
import { useWalletClient } from '../contexts/WalletServiceContext';

const OTimeUnits = {
  seconds: 'Seconds',
  minutes: 'Minutes',
  hours: 'Hours',
} as const;
type TimeUnits = (typeof OTimeUnits)[keyof typeof OTimeUnits];

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

  useInterval(fetchTransferOffers);

  const [acceptedOffers, setAcceptedOffers] = useState<Contract<AcceptedTransferOffer>[]>([]);
  const fetchAcceptedTransferOffers = useCallback(async () => {
    const { acceptedOffersList } = await listAcceptedTransferOffers();
    setAcceptedOffers(acceptedOffersList);
  }, [listAcceptedTransferOffers, setAcceptedOffers]);

  useInterval(fetchAcceptedTransferOffers);

  const [receiver, setReceiver] = useState<string>('');
  const [transferAmount, setTransferAmount] = useState<Decimal>(new Decimal(0.0));
  const [transferDescription, setTransferDescription] = useState('');
  const [transferExpirationValue, setTransferExpirationValue] = useState(new Decimal(0.0));
  const [transferExpirationUnit, setTransferExpirationUnit] = useState<TimeUnits>(
    OTimeUnits.seconds
  );
  const [idempotencyKey, setIdempotencyKey] = useState<string>('');
  const createOffer = async () => {
    const now = new Date();
    let expires = now;
    switch (transferExpirationUnit) {
      case OTimeUnits.seconds:
        expires = new Date(now.setSeconds(now.getSeconds() + transferExpirationValue.toNumber()));
        break;
      case OTimeUnits.minutes:
        expires = new Date(now.setMinutes(now.getMinutes() + transferExpirationValue.toNumber()));
        break;
      case OTimeUnits.hours:
        expires = new Date(now.setHours(now.getHours() + transferExpirationValue.toNumber()));
        break;
      default:
        throw Error(`Unexpected unit: ${transferExpirationUnit}`);
    }
    await createTransferOffer(
      receiver,
      transferAmount,
      transferDescription,
      expires,
      idempotencyKey
    );
  };

  const onReceiverChanged = async (newValue: Party) => {
    setReceiver(newValue);
  };

  const [createOfferOpen, setCreateOfferOpen] = useState(false);
  const openCreateOffer = () => {
    setTransferAmount(new Decimal(0));
    setTransferDescription('');
    setTransferExpirationValue(new Decimal(0));
    setTransferExpirationUnit(OTimeUnits.seconds);
    setIdempotencyKey(uuidv4());
    setCreateOfferOpen(true);
  };
  const closeCreateOffer = () => {
    setCreateOfferOpen(false);
  };
  const createTransferOfferAndClose = async () => {
    closeCreateOffer();
    await createOffer();
  };

  return (
    <Stack spacing={2}>
      <Button id="create-offer-button" onClick={openCreateOffer} variant="outlined">
        Create new offer
      </Button>
      <Dialog open={createOfferOpen} onClose={closeCreateOffer}>
        <DialogTitle>Create a Transfer Offer</DialogTitle>
        <DialogContent>
          <DirectoryField
            id="create-offer-receiver"
            label="Receiver"
            onPartyChanged={onReceiverChanged}
          />
          <TextField
            id="create-offer-amount"
            label="Amount"
            value={transferAmount}
            type="number"
            error={transferAmount.lessThanOrEqualTo(0.0)}
            onChange={event => setTransferAmount(new Decimal(event.target.value))}
            fullWidth
          />
          <TextField
            id="create-offer-description"
            label="Description"
            value={transferDescription}
            onChange={event => setTransferDescription(event.target.value)}
            fullWidth
          />
          <TextField
            id="create-offer-expiration-value"
            label="To expire in"
            value={transferExpirationValue}
            onChange={event => setTransferExpirationValue(new Decimal(event.target.value))}
            fullWidth
            type="number"
            error={transferExpirationValue.lessThanOrEqualTo(0.0)}
          />
          <FormControl>
            <Select
              id="create-offer-expiration-unit"
              value={transferExpirationUnit}
              onChange={(event: SelectChangeEvent<TimeUnits>) =>
                setTransferExpirationUnit(event.target.value as TimeUnits)
              }
            >
              {Object.values(OTimeUnits).map(val => (
                <MenuItem value={val} key={val}>
                  {val}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </DialogContent>
        <DialogActions>
          <Button
            id="submit-create-offer-button"
            onClick={createTransferOfferAndClose}
            disabled={
              transferAmount.lessThanOrEqualTo(0.0) ||
              transferExpirationValue.lessThanOrEqualTo(0.0)
            }
          >
            Submit
          </Button>
          <Button onClick={closeCreateOffer}>Cancel</Button>
        </DialogActions>
      </Dialog>
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
                <DirectoryEntryComponent partyId={c.payload.sender} />
              </TableCell>
              <TableCell className="transfer-offers-table-receiver">
                <DirectoryEntryComponent partyId={c.payload.receiver} />
              </TableCell>
              <TableCell className="transfer-offers-table-amount">
                <PaymentAmountDisplay amount={c.payload.amount} />
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
            <TableCell>Expiration</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {acceptedOffers.map(c => (
            <TableRow key={c.contractId} className="accepted-transfer-offers-row">
              <TableCell>
                <DirectoryEntryComponent partyId={c.payload.sender} />
              </TableCell>
              <TableCell>
                <DirectoryEntryComponent partyId={c.payload.receiver} />
              </TableCell>
              <TableCell>
                <PaymentAmountDisplay amount={c.payload.amount} />
              </TableCell>
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
