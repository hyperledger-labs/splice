import * as React from 'react';
import BigNumber from 'bignumber.js';
import {
  AmountDisplay,
  Contract,
  DirectoryEntry,
  useInterval,
  DateDisplay,
  useUserState,
} from 'common-frontend';
import { TransferOffer } from 'common-frontend/daml.js/wallet-0.1.0/lib/CN/Wallet/TransferOffer/module';
import Loading from 'common-frontend/lib/components/Loading';
import { useCallback, useState } from 'react';

import { ArrowCircleLeftOutlined } from '@mui/icons-material';
import { Button, Card, CardContent, Chip, Stack } from '@mui/material';
import Typography from '@mui/material/Typography';

import { Currency } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';

import { useCoinPrice } from '../contexts/CoinPriceContext';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { WalletTransferOffer } from '../models/models';
import { convertCurrency } from '../utils/currencyConversion';

export const TransferOffers: React.FC = () => {
  const { listTransferOffers } = useWalletClient();
  const [offers, setOffers] = useState<WalletTransferOffer[]>([]);
  const coinPrice = useCoinPrice();
  const { primaryPartyId } = useUserState();

  const toWalletTransferOffer = useCallback(
    async (
      offerList: Contract<TransferOffer>[],
      coinPrice: BigNumber
    ): Promise<WalletTransferOffer[]> => {
      return offerList
        .filter(o => o.payload.sender !== primaryPartyId)
        .map(offer => {
          return {
            contractId: offer.contractId,
            ccAmount: offer.payload.amount.amount,
            usdAmount: coinPrice ? coinPrice.times(offer.payload.amount.amount).toString() : '...',
            conversionRate: coinPrice ? coinPrice?.toString() : '...',
            convertedCurrency: convertCurrency(
              BigNumber(offer.payload.amount.amount),
              Currency.CC,
              coinPrice
            ),
            senderId: offer.payload.sender,
            expiry: offer.payload.expiresAt,
          };
        });
    },
    [primaryPartyId]
  );

  const fetchTransferOffers = useCallback(async () => {
    if (coinPrice) {
      const { offersList } = await listTransferOffers();
      let walletTransferOffers = await toWalletTransferOffer(offersList, coinPrice);
      setOffers(walletTransferOffers);
    }
  }, [coinPrice, listTransferOffers, toWalletTransferOffer]);

  useInterval(fetchTransferOffers);

  if (!coinPrice) {
    return <Loading />;
  }

  return (
    <Stack spacing={4} direction="column" justifyContent="center" id="transfer-offers">
      <Typography mt={6} variant="h4">
        Action needed{' '}
        <Chip label={offers.length} color="success" className="transfer-offers-count" />
      </Typography>
      {offers.map((offer, index) => (
        <TransferOfferDisplay key={'offer-' + index} transferOffer={offer} />
      ))}
    </Stack>
  );
};

interface TransferOfferProps {
  transferOffer: WalletTransferOffer;
}

export const TransferOfferDisplay: React.FC<TransferOfferProps> = props => {
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
            <DirectoryEntry
              partyId={offer.senderId}
              variant="h5"
              classNames={'transfer-offer-sender'}
            />
          </Stack>
        </Stack>
        <Stack direction="column" alignItems="flex-end">
          <Typography className="transfer-offer-cc-amount">
            + <AmountDisplay amount={BigNumber(offer.ccAmount)} currency="CC" />
          </Typography>
          <Typography className="transfer-offer-usd-amount-rate">
            <>
              <AmountDisplay
                amount={offer.convertedCurrency.amount}
                currency={offer.convertedCurrency.currency}
              />{' '}
              @ {offer.convertedCurrency.coinPriceToShow.toString()} CC/USD
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
