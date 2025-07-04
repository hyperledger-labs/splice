// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  AmountDisplay,
  DateDisplay,
  ErrorDisplay,
  Loading,
} from '@lfdecentralizedtrust/splice-common-frontend';
import BigNumber from 'bignumber.js';
import { useCallback, useMemo, useState } from 'react';

import { ArrowCircleLeftOutlined } from '@mui/icons-material';
import { Box, Button, Card, CardContent, Chip, Stack } from '@mui/material';
import Typography from '@mui/material/Typography';

import { AmuletTransferInstruction } from '@daml.js/splice-amulet-0.1.9/lib/Splice/AmuletTransferInstruction';
import { Unit } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';
import { TransferOffer } from '@daml.js/splice-wallet/lib/Splice/Wallet/TransferOffer/module';
import { ContractId } from '@daml/types';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { usePrimaryParty, useTransferOffers } from '../hooks';
import useAmuletPrice from '../hooks/scan-proxy/useAmuletPrice';
import { useTokenStandardTransfers } from '../hooks/useTokenStandardTransfers';
import { WalletTransferOffer } from '../models/models';
import { useWalletConfig } from '../utils/config';
import { convertCurrency } from '../utils/currencyConversion';
import BftAnsEntry from './BftAnsEntry';

type PartialWalletTransferOffer = {
  contractId: ContractId<TransferOffer> | ContractId<AmuletTransferInstruction>;
  amount: string;
  sender: string;
  expiresAt: string;
  isTokenStandard: boolean;
  receiver: string;
};

type TransferOffersListProps = {
  mode: 'sent' | 'received';
};

export const TransferOffers: React.FC<TransferOffersListProps> = ({ mode }) => {
  const [offers, setOffers] = useState<WalletTransferOffer[]>([]);
  const amuletPriceQuery = useAmuletPrice();
  const primaryPartyId = usePrimaryParty();

  const toWalletTransferOffer = useCallback(
    async (
      items: Array<PartialWalletTransferOffer>,
      amuletPrice: BigNumber
    ): Promise<WalletTransferOffer[]> => {
      return items
        .filter(item =>
          mode === 'sent' ? item.sender === primaryPartyId : item.sender !== primaryPartyId
        )
        .map(item => {
          return {
            contractId: item.contractId,
            ccAmount: item.amount,
            usdAmount: amuletPrice ? amuletPrice.times(item.amount).toString() : '...',
            conversionRate: amuletPrice ? amuletPrice?.toString() : '...',
            convertedCurrency: convertCurrency(
              BigNumber(item.amount),
              Unit.AmuletUnit,
              amuletPrice
            ),
            senderId: item.sender,
            receiverId: item.receiver,
            expiry: item.expiresAt,
            isTokenStandard: item.isTokenStandard,
          };
        });
    },
    [primaryPartyId, mode]
  );

  const transferOfferContractsQuery = useTransferOffers(amuletPriceQuery.data);
  const { data: transferOfferContracts } = transferOfferContractsQuery;
  const tokenStandardTransfersQuery = useTokenStandardTransfers();
  const { data: tokenStandardTransferContracts } = tokenStandardTransfersQuery;
  const amuletPrice = amuletPriceQuery.data;

  useMemo(() => {
    if (transferOfferContracts && tokenStandardTransferContracts && amuletPrice) {
      const allTransfers: PartialWalletTransferOffer[] = transferOfferContracts
        .map(offer => {
          const item: PartialWalletTransferOffer = {
            isTokenStandard: false,
            contractId: offer.contractId,
            amount: offer.payload.amount.amount,
            sender: offer.payload.sender,
            receiver: offer.payload.receiver,
            expiresAt: offer.payload.expiresAt,
          };
          return item;
        })
        .concat(
          tokenStandardTransferContracts.map(transfer => {
            const item: PartialWalletTransferOffer = {
              isTokenStandard: true,
              contractId: transfer.contractId,
              amount: transfer.payload.transfer.amount,
              sender: transfer.payload.transfer.sender,
              receiver: transfer.payload.transfer.receiver,
              expiresAt: transfer.payload.transfer.executeBefore,
            };
            return item;
          })
        );
      toWalletTransferOffer(allTransfers, amuletPrice).then(setOffers);
    }
  }, [amuletPrice, toWalletTransferOffer, transferOfferContracts, tokenStandardTransferContracts]);

  const isLoading =
    amuletPriceQuery.isLoading ||
    transferOfferContractsQuery.isLoading ||
    tokenStandardTransfersQuery.isLoading;
  const isError =
    amuletPriceQuery.isError ||
    transferOfferContractsQuery.isError ||
    tokenStandardTransfersQuery.isError;
  const heading = mode === 'sent' ? 'Pending Offers ' : 'Action Needed ';

  return (
    <Stack spacing={4} direction="column" justifyContent="center" id="transfer-offers">
      <Typography mt={6} variant="h4">
        {heading}
        <Chip label={offers.length} color="success" className="transfer-offers-count" />
      </Typography>
      {isLoading ? (
        <Loading />
      ) : isError ? (
        <ErrorDisplay message={'Error while fetching amulet price and transfer offers'} />
      ) : offers.length === 0 ? (
        <Box display="flex" justifyContent="center">
          <Typography variant="h6">No transfer offers available</Typography>
        </Box>
      ) : (
        offers.map((offer, index) => (
          <TransferOfferDisplay key={'offer-' + index} transferOffer={offer} mode={mode} />
        ))
      )}
    </Stack>
  );
};

interface TransferOfferProps {
  transferOffer: WalletTransferOffer;
  mode: 'sent' | 'received';
}

export const TransferOfferDisplay: React.FC<TransferOfferProps> = props => {
  const config = useWalletConfig();
  const offer = props.transferOffer;
  const mode = props.mode;

  const {
    acceptTransferOffer,
    rejectTransferOffer,
    acceptTokenStandardTransfer,
    rejectTokenStandardTransfer,
  } = useWalletClient();
  const accept = offer.isTokenStandard ? acceptTokenStandardTransfer : acceptTransferOffer;
  const reject = offer.isTokenStandard ? rejectTokenStandardTransfer : rejectTransferOffer;

  return (
    <Card className={mode === 'sent' ? 'pending-offer' : 'transfer-offer'} variant="outlined">
      <CardContent
        sx={{
          display: 'flex',
          direction: 'row',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <ArrowCircleLeftOutlined fontSize="large" />
        <Stack direction="row" alignItems="center">
          <Stack direction="column">
            {mode === 'received' ? (
              <BftAnsEntry
                partyId={offer.senderId}
                variant="h5"
                className={'transfer-offer-sender'}
              />
            ) : (
              <BftAnsEntry
                partyId={offer.receiverId}
                variant="h5"
                className={'transfer-offer-receiver'}
              />
            )}
          </Stack>
        </Stack>
        <Stack direction="column" alignItems="flex-end">
          <Typography className="transfer-offer-amulet-amount">
            + <AmountDisplay amount={BigNumber(offer.ccAmount)} currency="AmuletUnit" />
          </Typography>
          <Typography className="transfer-offer-usd-amount-rate">
            <>
              <AmountDisplay
                amount={offer.convertedCurrency.amount}
                currency={offer.convertedCurrency.currency}
              />{' '}
              @ {offer.convertedCurrency.amuletPriceToShow.toString()}{' '}
              {config.spliceInstanceNames.amuletNameAcronym}/USD
            </>
          </Typography>
        </Stack>
        <Stack direction="row" alignItems="center" spacing={2}>
          {mode !== 'sent' ? (
            <>
              <Button
                variant="pill"
                size="small"
                onClick={() => accept(offer.contractId)}
                className="transfer-offer-accept"
              >
                Accept
              </Button>
              <Button
                variant="pill"
                color="warning"
                size="small"
                onClick={() => reject(offer.contractId)}
                className="transfer-offer-reject"
              >
                Reject
              </Button>
            </>
          ) : null}
        </Stack>
        <Typography variant="caption" className="transfer-offer-expiry">
          Expires <DateDisplay datetime={offer.expiry} />
        </Typography>
      </CardContent>
    </Card>
  );
};
