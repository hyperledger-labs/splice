// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  AmountDisplay,
  DateDisplay,
  DisableConditionally,
  ErrorDisplay,
  Loading,
} from '@lfdecentralizedtrust/splice-common-frontend';
import BigNumber from 'bignumber.js';
import { useCallback, useMemo, useState } from 'react';

import { ArrowCircleLeftOutlined } from '@mui/icons-material';
import { Box, Button, Card, CardContent, Chip, Stack } from '@mui/material';
import Typography from '@mui/material/Typography';

import { AmuletTransferInstruction } from '@daml.js/splice-amulet/lib/Splice/AmuletTransferInstruction';
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
import { useMutation } from '@tanstack/react-query';

type PartialWalletTransferOffer = {
  contractId: ContractId<TransferOffer> | ContractId<AmuletTransferInstruction>;
  amount: string;
  sender: string;
  expiresAt: string;
  isTokenStandard: boolean;
};
export const TransferOffers: React.FC = () => {
  const [offers, setOffers] = useState<WalletTransferOffer[]>([]);
  const amuletPriceQuery = useAmuletPrice();
  const primaryPartyId = usePrimaryParty();

  const toWalletTransferOffer = useCallback(
    async (
      items: Array<PartialWalletTransferOffer>,
      amuletPrice: BigNumber
    ): Promise<WalletTransferOffer[]> => {
      return items
        .filter(item => item.sender !== primaryPartyId)
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
            expiry: item.expiresAt,
            isTokenStandard: item.isTokenStandard,
          };
        });
    },
    [primaryPartyId]
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

// An accept or a reject can fail if for example the transfer offer is visible to the validator,
// but not to f+1 Scans (which are required to get the accept/reject context from).
// Or overall other transient errors.
// Ultimately, it should go through so as long as it's visible to the validator.
// Our default retry waiting time is `1s * 1.5^attempt`, which sums up to ~13s over 5 attempts.
// Longer timeouts are not recommended as the user might give up and reload the page to try again manually.
const TRANSFER_OFFER_ACTION_RETRIES_COUNT = 5;

export const TransferOfferDisplay: React.FC<TransferOfferProps> = props => {
  const config = useWalletConfig();
  const offer = props.transferOffer;
  const {
    acceptTransferOffer,
    rejectTransferOffer,
    acceptTokenStandardTransfer,
    rejectTokenStandardTransfer,
  } = useWalletClient();

  const acceptMutation = useMutation({
    mutationFn: async () => {
      await (offer.isTokenStandard
        ? acceptTokenStandardTransfer(offer.contractId)
        : acceptTransferOffer(offer.contractId));
    },
    onError: error => {
      console.info(`Failed to accept transfer offer ${offer.contractId}:`, error);
    },
    retry: TRANSFER_OFFER_ACTION_RETRIES_COUNT,
  });
  const rejectMutation = useMutation({
    mutationFn: async () => {
      await (offer.isTokenStandard
        ? rejectTokenStandardTransfer(offer.contractId)
        : rejectTransferOffer(offer.contractId));
    },
    onError: error => {
      console.info(`Failed to reject transfer offer ${offer.contractId}:`, error);
    },
    retry: TRANSFER_OFFER_ACTION_RETRIES_COUNT,
  });

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
          <DisableConditionally
            conditions={[
              {
                disabled: acceptMutation.isPending,
                reason: 'Accepting...',
              },
            ]}
          >
            <Button
              variant="pill"
              size="small"
              onClick={() => acceptMutation.mutate()}
              className="transfer-offer-accept"
            >
              Accept
            </Button>
          </DisableConditionally>
          <DisableConditionally
            conditions={[
              {
                disabled: rejectMutation.isPending,
                reason: 'Rejecting...',
              },
            ]}
          >
            <Button
              variant="pill"
              color="warning"
              size="small"
              onClick={() => rejectMutation.mutate()}
              className="transfer-offer-reject"
            >
              Reject
            </Button>
          </DisableConditionally>
        </Stack>
        <Typography variant="caption" className="transfer-offer-expiry">
          Expires <DateDisplay datetime={offer.expiry} />
        </Typography>
      </CardContent>
    </Card>
  );
};
