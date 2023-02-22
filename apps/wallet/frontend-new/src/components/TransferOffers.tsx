import * as React from 'react';

import { ArrowCircleLeftOutlined } from '@mui/icons-material';
import { Button, Card, CardContent, Chip, Stack } from '@mui/material';
import Typography from '@mui/material/Typography';

import { TransferOffer } from '../models/models';
import AmountDisplay from './AmountDisplay';

export const TransferOffers: React.FC = () => {
  const offers: TransferOffer[] = [
    {
      totalCCAmount: '31',
      totalUSDAmount: '372',
      conversionRate: '12',
      senderId: 'KeyKay',
      providerId: 'Splitwise',
      expiry: 'Expires Feb 05, 2023',
    },
    {
      totalCCAmount: '10',
      totalUSDAmount: '120',
      conversionRate: '12',
      senderId: 'KeyKay',
      providerId: 'Splitwise',
      expiry: 'Expires Mar 06, 2023',
    },
    {
      totalCCAmount: '25',
      totalUSDAmount: '300',
      conversionRate: '12',
      senderId: 'KeyKay',
      providerId: 'Splitwise',
      expiry: 'Expires Apr 07, 2023',
    },
  ];

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Action needed <Chip label="8" color="success" />
      </Typography>
      {offers.map((offer, index) => (
        <TransferOfferDisplay key={'offer-' + index} transferOffer={offer} />
      ))}
    </Stack>
  );
};

interface TransferOfferProps {
  transferOffer: TransferOffer;
}

export const TransferOfferDisplay: React.FC<TransferOfferProps> = props => {
  const offer = props.transferOffer;
  return (
    <Card variant="outlined">
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
            <Typography variant="h5">{offer.senderId}</Typography>
            <Typography variant="caption">via {offer.providerId}</Typography>
          </Stack>
        </Stack>
        <Stack direction="column" alignItems="flex-end">
          <Typography>
            + <AmountDisplay amount={offer.totalCCAmount} />
          </Typography>
          <Typography>
            <AmountDisplay amount={offer.totalUSDAmount} currency={'USD'} /> @{' '}
            {offer.conversionRate} CC/USD
          </Typography>
        </Stack>
        <Stack direction="row" alignItems="center" spacing={2}>
          <Button variant="pill" size="small">
            Accept
          </Button>
          <Button variant="pill" color="warning" size="small">
            Reject
          </Button>
          <Typography variant="caption">{offer.expiry}</Typography>
        </Stack>
      </CardContent>
    </Card>
  );
};
