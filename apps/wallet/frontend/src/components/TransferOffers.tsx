// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  AmountDisplay,
  DateDisplay,
  ErrorDisplay,
  Loading,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import BigNumber from 'bignumber.js';
import { useCallback, useMemo, useState } from 'react';

import { ArrowCircleLeftOutlined } from '@mui/icons-material';
import { Box, Button, Card, CardContent, Chip, Stack } from '@mui/material';
import Typography from '@mui/material/Typography';

import { Unit } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';
import { TransferOffer } from '@daml.js/splice-wallet/lib/Splice/Wallet/TransferOffer/module';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { usePrimaryParty, useTransferOffers } from '../hooks';
import useAmuletPrice from '../hooks/scan-proxy/useAmuletPrice';
import { WalletTransferOffer } from '../models/models';
import { useWalletConfig } from '../utils/config';
import { convertCurrency } from '../utils/currencyConversion';
import BftAnsEntry from './BftAnsEntry';

export const TransferOffers: React.FC = () => {
  const [offers, setOffers] = useState<WalletTransferOffer[]>([]);
  const amuletPriceQuery = useAmuletPrice();
  const primaryPartyId = usePrimaryParty();

  const toWalletTransferOffer = useCallback(
    async (
      offerList: Contract<TransferOffer>[],
      amuletPrice: BigNumber
    ): Promise<WalletTransferOffer[]> => {
      return offerList
        .filter(o => o.payload.sender !== primaryPartyId)
        .map(offer => {
          return {
            contractId: offer.contractId,
            ccAmount: offer.payload.amount.amount,
            usdAmount: amuletPrice
              ? amuletPrice.times(offer.payload.amount.amount).toString()
              : '...',
            conversionRate: amuletPrice ? amuletPrice?.toString() : '...',
            convertedCurrency: convertCurrency(
              BigNumber(offer.payload.amount.amount),
              Unit.AmuletUnit,
              amuletPrice
            ),
            senderId: offer.payload.sender,
            expiry: offer.payload.expiresAt,
          };
        });
    },
    [primaryPartyId]
  );

  const transferOfferContractsQuery = useTransferOffers(amuletPriceQuery.data);
  const { data: transferOfferContracts } = transferOfferContractsQuery;
  const amuletPrice = amuletPriceQuery.data;

  useMemo(() => {
    if (transferOfferContracts && amuletPrice) {
      toWalletTransferOffer(transferOfferContracts, amuletPrice).then(setOffers);
    }
  }, [amuletPrice, toWalletTransferOffer, transferOfferContracts]);

  const isLoading = amuletPriceQuery.isLoading || transferOfferContractsQuery.isLoading;
  const isError = amuletPriceQuery.isError || transferOfferContractsQuery.isError;

  return (
    <Stack spacing={4} direction="column" justifyContent="center" id="transfer-offers">
      <Typography mt={6} variant="h4">
        Action Needed{' '}
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
          <TransferOfferDisplay key={'offer-' + index} transferOffer={offer} />
        ))
      )}
    </Stack>
  );
};

interface TransferOfferProps {
  transferOffer: WalletTransferOffer;
}

export const TransferOfferDisplay: React.FC<TransferOfferProps> = props => {
  const config = useWalletConfig();
  const offer = props.transferOffer;
  const { acceptTransferOffer, rejectTransferOffer } = useWalletClient();

  return (
    <Card className="transfer-offer" variant="outlined">
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
            <BftAnsEntry
              partyId={offer.senderId}
              variant="h5"
              className={'transfer-offer-sender'}
            />
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
          <Button
            variant="pill"
            size="small"
            onClick={() => acceptTransferOffer(offer.contractId)}
            className="transfer-offer-accept"
          >
            Accept
          </Button>
          <Button
            variant="pill"
            color="warning"
            size="small"
            onClick={() => rejectTransferOffer(offer.contractId)}
            className="transfer-offer-reject"
          >
            Reject
          </Button>
        </Stack>
        <Typography variant="caption" className="transfer-offer-expiry">
          Expires <DateDisplay datetime={offer.expiry} />
        </Typography>
      </CardContent>
    </Card>
  );
};
