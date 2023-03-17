import * as React from 'react';
import BigNumber from 'bignumber.js';
import { AmountDisplay, Contract, DirectoryEntry, useInterval } from 'common-frontend';
import { TransferOffer } from 'common-frontend/daml.js/wallet-0.1.0/lib/CN/Wallet/TransferOffer/module';
import DateDisplay from 'common-frontend/lib/components/DateDisplay';
import { useCallback, useState } from 'react';

import { ArrowCircleLeftOutlined } from '@mui/icons-material';
import { Button, Card, CardContent, Chip, Stack } from '@mui/material';
import Typography from '@mui/material/Typography';

import { Currency } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';

import { useCoinPrice } from '../contexts/CoinPriceContext';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { WalletTransferOffer } from '../models/models';
import { convertCurrency } from '../utils/currencyConversion';
import Loading from './Loading';

export const TransferOffers: React.FC = () => {
  const { listTransferOffers } = useWalletClient();
  const [offers, setOffers] = useState<WalletTransferOffer[]>([]);
  const coinPrice = useCoinPrice();

  const toWalletTransferOffer = useCallback(
    async (
      offerList: Contract<TransferOffer>[],
      coinPrice: BigNumber
    ): Promise<WalletTransferOffer[]> => {
      return offerList.map(offer => {
        return {
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
    []
  );

  const fetchTransferOffers = useCallback(async () => {
    if (coinPrice) {
      const { offersList } = await listTransferOffers();
      let walletTransferOffers = await toWalletTransferOffer(offersList, coinPrice);
      setOffers(walletTransferOffers);
    }
  }, [coinPrice, listTransferOffers, toWalletTransferOffer]);

  // TODO(#3434) remove magic interval
  useInterval(fetchTransferOffers, 500);

  if (!coinPrice) {
    return <Loading />;
  }

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Action needed <Chip label={offers.length} color="success" />
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
            <DirectoryEntry partyId={offer.senderId} classNames={'transfer-offer-sender'} />
          </Stack>
        </Stack>
        <Stack direction="column" alignItems="flex-end">
          <Typography className="transfer-offer-cc-amount">
            + <AmountDisplay amount={offer.ccAmount} />
          </Typography>
          <Typography className="transfer-offer-usd-amount-rate">
            <>
              <AmountDisplay
                amount={offer.convertedCurrency.amount.toString()}
                currency={offer.convertedCurrency.currency}
              />{' '}
              @ {offer.convertedCurrency.coinPriceToShow.toString()} CC/USD
            </>
          </Typography>
        </Stack>
        <Stack direction="row" alignItems="center" spacing={2}>
          <Button variant="pill" size="small">
            Accept
          </Button>
          <Button variant="pill" color="warning" size="small">
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
