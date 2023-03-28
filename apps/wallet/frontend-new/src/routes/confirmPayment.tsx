import BigNumber from 'bignumber.js';
import { AmountDisplay, DirectoryEntry } from 'common-frontend';
import React, { useEffect, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';

import { ArrowOutward } from '@mui/icons-material';
import { Box, Button, Container, Stack, styled, Typography } from '@mui/material';

import * as payment from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';
import { ContractId } from '@daml/types';

import Loading from '../components/Loading';
import PaymentHeader from '../components/PaymentHeader';
import { useCoinPrice } from '../contexts/CoinPriceContext';
import { useWalletClient } from '../contexts/WalletServiceContext';
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

  const coinPrice = useCoinPrice();

  if (!appPayment || !coinPrice) {
    return <Loading />;
  }

  const { appPaymentRequest, deliveryOffer } = appPayment;
  // TODO (#3485): handle multiple recipients
  return (
    <Box display="flex" flexDirection="column" minHeight="100vh" id="confirm-payment">
      <PaymentHeader />
      <Box bgcolor="colors.neutral.25" flex={1}>
        <Container maxWidth="sm">
          <Stack alignItems="center" paddingTop={4} spacing={4}>
            <RecipientInfo
              amount={appPaymentRequest.payload.receiverAmounts[0].amount}
              receiver={appPaymentRequest.payload.receiverAmounts[0].receiver}
              provider={appPaymentRequest.payload.provider}
            />
            <PaymentDescription description={deliveryOffer.payload.description} />
            <PaymentContainer
              contractId={appPaymentRequest.contractId}
              amount={appPaymentRequest.payload.receiverAmounts[0].amount}
              fee={new BigNumber(0)} // TODO (#3492): compute actual fee
              coinPrice={coinPrice}
            />
          </Stack>
        </Container>
      </Box>
    </Box>
  );
};

export default ConfirmPayment;

interface RecipientInfoProps {
  amount: payment.PaymentAmount;
  receiver: string;
  provider: string;
}
const RecipientInfo: React.FC<RecipientInfoProps> = ({ amount, receiver, provider }) => {
  return (
    <Stack alignItems="center" spacing={1}>
      <SendPaymentIcon />
      <Typography variant="h5" className="payment-amount">
        Send <AmountDisplay amount={amount.amount} currency={amount.currency} /> to{' '}
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
  amount: payment.PaymentAmount;
  fee: BigNumber;
  coinPrice: BigNumber;
}
const PaymentContainer: React.FC<PaymentContainerProps> = ({
  contractId,
  amount,
  fee,
  coinPrice,
}) => {
  const converted = convertCurrency(new BigNumber(amount.amount), amount.currency, coinPrice);
  const ccAmount = amount.currency === 'CC' ? new BigNumber(amount.amount) : converted.amount;

  const totalCC = ccAmount.plus(fee);
  const totalUSD = totalCC.times(coinPrice);

  return (
    <Container>
      <Box bgcolor="colors.neutral.20" border={1} borderColor="colors.neutral.30">
        <Stack alignItems="center" spacing={4} marginY={4}>
          <Typography variant="body1">{"You'll pay:"}</Typography>
          <Stack alignItems="center">
            <Typography variant="h5" className="payment-total-cc">
              <AmountDisplay amount={totalCC.toString()} currency={'CC'} />
            </Typography>
            <Typography variant="body2" className="payment-compute">
              <AmountDisplay amount={ccAmount.toString()} currency={'CC'} /> +{' '}
              <AmountDisplay amount={fee.toString()} currency={'CC'} /> fee /{' '}
              <AmountDisplay amount={totalUSD.toString()} currency={'USD'} />
            </Typography>
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
