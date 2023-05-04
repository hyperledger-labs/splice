import BigNumber from 'bignumber.js';
import { AmountDisplay, DirectoryEntry, RateDisplay, Loading } from 'common-frontend';
import React, { useEffect, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';

import { ArrowOutward } from '@mui/icons-material';
import {
  Box,
  Button,
  Container,
  Stack,
  styled,
  Table,
  TableBody,
  TableCell,
  TableRow,
  Typography,
} from '@mui/material';

import * as payment from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';
import { Currency, ReceiverAmount } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';
import { ContractId } from '@daml/types';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { useCoinPrice } from '../hooks';
import { AppPaymentRequest } from '../models/models';
import { convertCurrency } from '../utils/currencyConversion';

export const ConfirmPayment: React.FC = () => {
  const { getAppPaymentRequest } = useWalletClient();
  const { cid } = useParams();
  const [appPayment, setAppPayment] = useState<AppPaymentRequest>();

  // TODO (#3333): react-query already does retries
  useEffect(() => {
    let timer: NodeJS.Timeout | undefined;
    const fetchAppPayment = async (n: number) => {
      await getAppPaymentRequest(cid!).then(
        appPayment => {
          console.debug('Payment request found');
          setAppPayment(appPayment);
        },
        err => {
          console.debug('Failed to get payment request, trying again...', err);
          timer = setTimeout(fetchAppPayment, 500, n - 1);
        }
      );
    };
    fetchAppPayment(30);

    return () => {
      if (timer !== undefined) clearTimeout(timer);
    };
  }, [cid, getAppPaymentRequest]);

  const coinPriceQuery = useCoinPrice();

  if (!appPayment || coinPriceQuery.isLoading) {
    return <Loading />;
  }

  // TODO(#4139) implement error state from design
  if (coinPriceQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const { appPaymentRequest, deliveryOffer } = appPayment;

  const total = computeTotal(
    appPayment.appPaymentRequest.payload.receiverAmounts,
    coinPriceQuery.data
  );

  if (!total) {
    console.error('No receivers in app payment.');
    return <>No receivers in app payment.</>;
  }

  const isSingleRecipient = appPaymentRequest.payload.receiverAmounts.length === 1;
  const recipientInfo = isSingleRecipient ? (
    <SingleRecipientInfo
      amount={appPaymentRequest.payload.receiverAmounts[0].amount}
      receiver={appPaymentRequest.payload.receiverAmounts[0].receiver}
      provider={appPaymentRequest.payload.provider}
    />
  ) : (
    <MultiRecipientsInfo
      receiverAmounts={appPaymentRequest.payload.receiverAmounts}
      currencyForAllReceivers={total.currencyForAllReceivers}
      coinPrice={coinPriceQuery.data}
      provider={appPaymentRequest.payload.provider}
    />
  );

  return (
    <Container maxWidth="sm">
      <Stack alignItems="center" paddingTop={4} spacing={4}>
        {recipientInfo}
        <PaymentDescription description={deliveryOffer.payload.description} />
        <TotalPaymentContainer
          contractId={appPaymentRequest.contractId}
          total={total}
          coinPrice={coinPriceQuery.data}
        />
      </Stack>
    </Container>
  );
};

type BothCurrencies = 'CC & USD';
interface Total {
  totalAmount: BigNumber;
  /**
   * The currency of `totalAmount`.
   */
  totalCurrency: Currency;
  /**
   * The currency in which all the receivers are paid.
   */
  currencyForAllReceivers: Currency | BothCurrencies;
}
function computeTotal(receiverAmounts: ReceiverAmount[], coinPrice: BigNumber): Total | undefined {
  // The currency is NOT necessarily the same for all receivers
  const { totalCC, totalUSD } = receiverAmounts.reduce(
    (acc, next) => {
      let newAcc;
      switch (next.amount.currency) {
        case 'CC':
          newAcc = { totalCC: acc.totalCC.plus(next.amount.amount), totalUSD: acc.totalUSD };
          break;
        case 'USD':
          newAcc = { totalUSD: acc.totalUSD.plus(next.amount.amount), totalCC: acc.totalCC };
          break;
      }
      return newAcc;
    },
    {
      totalCC: new BigNumber(0),
      totalUSD: new BigNumber(0),
    }
  );

  if (totalCC.eq(0) && totalUSD.eq(0)) {
    return undefined;
  } else if (totalUSD.eq(0)) {
    // everything is in CC
    return { totalCurrency: 'CC', totalAmount: totalCC, currencyForAllReceivers: 'CC' };
  } else if (totalCC.eq(0)) {
    // everything is in USD
    return { totalCurrency: 'USD', totalAmount: totalUSD, currencyForAllReceivers: 'USD' };
  } else {
    // both, we use CC to show the total amount
    const totalAmount = totalUSD.div(coinPrice).plus(totalCC);
    return { totalCurrency: 'CC', totalAmount, currencyForAllReceivers: 'CC & USD' };
  }
}

export default ConfirmPayment;

interface SingleRecipientInfoProps {
  amount: payment.PaymentAmount;
  receiver: string;
  provider: string;
}
const SingleRecipientInfo: React.FC<SingleRecipientInfoProps> = ({
  amount,
  receiver,
  provider,
}) => {
  return (
    <Stack alignItems="center" spacing={1}>
      <SendPaymentIcon />
      <Typography variant="h5" className="payment-amount">
        Send <AmountDisplay amount={BigNumber(amount.amount)} currency={amount.currency} /> to{' '}
      </Typography>
      <DirectoryEntry
        partyId={receiver}
        variant="h5"
        fontWeight="bold"
        classNames="payment-receiver"
      />
      <Stack direction="row" alignItems="center" spacing={1}>
        <Typography variant="body2">via</Typography>{' '}
        <DirectoryEntry partyId={provider} variant="body2" classNames="payment-provider" />
      </Stack>
    </Stack>
  );
};

interface MultipleRecipientsInfoProps {
  receiverAmounts: ReceiverAmount[];
  currencyForAllReceivers: Currency | BothCurrencies;
  coinPrice: BigNumber;
  provider: string;
}

const MultiRecipientsInfo: React.FC<MultipleRecipientsInfoProps> = ({
  currencyForAllReceivers,
  receiverAmounts,
  coinPrice,
  provider,
}) => {
  return (
    <Stack alignItems="center" spacing={1}>
      <SendPaymentIcon />
      <Typography variant="h5" className="payment-amount">
        Send {currencyForAllReceivers} to multiple recipients
      </Typography>
      <Stack direction="row" alignItems="center" spacing={1}>
        <Typography variant="body2">via</Typography>{' '}
        <DirectoryEntry partyId={provider} variant="body2" classNames="payment-provider" />
      </Stack>
      <Table>
        <TableBody>
          {receiverAmounts.map(({ amount: { amount, currency }, receiver }) => {
            const converted = convertCurrency(new BigNumber(amount), currency, coinPrice);
            return (
              <TableRow key={receiver} id={`${receiver}-payment-row`}>
                <TableCell variant="party">
                  <DirectoryEntry partyId={receiver} variant="h6" classNames="receiver-entry" />
                </TableCell>
                <TableCell>
                  <Typography variant="h6" className="receiver-amount">
                    <AmountDisplay amount={BigNumber(amount)} currency={currency} />
                  </Typography>
                </TableCell>
                <TableCell>
                  <Typography variant="caption" className="receiver-amount-converted">
                    <AmountDisplay amount={converted.amount} currency={converted.currency} />
                  </Typography>
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </Stack>
  );
};

const SendPaymentIcon = styled(ArrowOutward)({
  border: '1px solid #fff',
  borderRadius: '50%',
});

interface PaymentDescriptionProps {
  description: string;
}
const PaymentDescription: React.FC<PaymentDescriptionProps> = ({ description }) => {
  return (
    <Stack alignItems="center">
      <Typography variant="body1">Description:</Typography>
      <Typography variant="body1" className="payment-description">
        &quot;{description}&quot;
      </Typography>
    </Stack>
  );
};

interface PaymentContainerProps {
  contractId: ContractId<payment.AppPaymentRequest>;
  total: Total;
  coinPrice: BigNumber;
}
const TotalPaymentContainer: React.FC<PaymentContainerProps> = ({
  contractId,
  total,
  coinPrice,
}) => {
  const converted = convertCurrency(total.totalAmount, total.totalCurrency, coinPrice);
  const ccAmount = total.totalCurrency === 'CC' ? total.totalAmount : converted.amount;

  const totalCC = ccAmount; // TODO (#3492): compute actual fee
  const totalUSD = totalCC.times(coinPrice);

  return (
    <Container>
      <Box bgcolor="colors.neutral.20" border={1} borderColor="colors.neutral.30">
        <Stack alignItems="center" spacing={4} marginY={4}>
          <Typography variant="body1">{"You'll pay:"}</Typography>
          <Stack alignItems="center">
            <Typography variant="h5" className="payment-total-cc">
              <AmountDisplay amount={totalCC} currency={'CC'} />
            </Typography>
            <Typography variant="body2" className="payment-compute">
              <AmountDisplay amount={totalUSD} currency={'USD'} /> @{' '}
              <RateDisplay
                base={total.totalCurrency}
                quote={converted.currency}
                coinPrice={coinPrice}
              />
            </Typography>
            <Typography variant="body2">Fees will be added.</Typography>
          </Stack>
          <ConfirmPaymentButton contractId={contractId} />
        </Stack>
      </Box>
    </Container>
  );
};

const ConfirmPaymentButton: React.FC<{ contractId: ContractId<payment.AppPaymentRequest> }> = ({
  contractId,
}) => {
  const { acceptAppPaymentRequest } = useWalletClient();
  const [searchParams] = useSearchParams();
  const redirect = searchParams.get('redirect');

  const onAccept = async () => {
    await acceptAppPaymentRequest(contractId);
    if (redirect) {
      window.location.assign(redirect);
    }
  };

  return (
    <Button variant="pill" size="large" onClick={onAccept} className="payment-accept">
      Send Payment
    </Button>
  );
};
