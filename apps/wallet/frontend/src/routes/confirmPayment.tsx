// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AmountDisplay,
  ErrorDisplay,
  Loading,
  RateDisplay,
} from '@lfdecentralizedtrust/splice-common-frontend';
import BigNumber from 'bignumber.js';
import React from 'react';
import { useParams, useSearchParams } from 'react-router';

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

import * as payment from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';
import { Unit, ReceiverAmount } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';
import { ContractId } from '@daml/types';

import BftAnsEntry from '../components/BftAnsEntry';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { useAppPaymentRequest } from '../hooks';
import useAmuletPrice from '../hooks/scan-proxy/useAmuletPrice';
import { convertCurrency } from '../utils/currencyConversion';

export const ConfirmPayment: React.FC = () => {
  const { cid } = useParams();
  const amuletPriceQuery = useAmuletPrice();
  const appPaymentRequestQuery = useAppPaymentRequest(cid!);
  const isDataUndefined =
    appPaymentRequestQuery.data === undefined || amuletPriceQuery.data === undefined;

  if (appPaymentRequestQuery.isLoading || amuletPriceQuery.isLoading) {
    return <Loading />;
  }

  if (amuletPriceQuery.isError || appPaymentRequestQuery.isError || isDataUndefined) {
    return <ErrorDisplay message={'Error while fetching payment requests and amulet price'} />;
  }

  const appPaymentRequest = appPaymentRequestQuery.data.contract;

  const total = computeTotal(appPaymentRequest.payload.receiverAmounts, amuletPriceQuery.data);

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
      amuletPrice={amuletPriceQuery.data}
      provider={appPaymentRequest.payload.provider}
    />
  );

  return (
    <Container maxWidth="sm">
      <Stack alignItems="center" paddingTop={4} spacing={4}>
        {recipientInfo}
        <PaymentDescription description={appPaymentRequest.payload.description} />
        <TotalPaymentContainer
          contractId={appPaymentRequest.contractId}
          total={total}
          amuletPrice={amuletPriceQuery.data}
        />
      </Stack>
    </Container>
  );
};

type BothUnits = 'AmuletUnit & USDUnit';
interface Total {
  totalAmount: BigNumber;
  /**
   * The currency of `totalAmount`.
   */
  totalCurrency: Unit;
  /**
   * The currency in which all the receivers are paid.
   */
  currencyForAllReceivers: Unit | BothUnits;
}
function computeTotal(
  receiverAmounts: ReceiverAmount[],
  amuletPrice: BigNumber
): Total | undefined {
  // The currency is NOT necessarily the same for all receivers
  const { totalAmulet, totalUSDUnit } = receiverAmounts.reduce(
    (acc, next) => {
      let newAcc;
      switch (next.amount.unit) {
        case 'AmuletUnit':
          newAcc = {
            totalAmulet: acc.totalAmulet.plus(next.amount.amount),
            totalUSDUnit: acc.totalUSDUnit,
          };
          break;
        case 'USDUnit':
          newAcc = {
            totalUSDUnit: acc.totalUSDUnit.plus(next.amount.amount),
            totalAmulet: acc.totalAmulet,
          };
          break;
        case 'ExtUnit':
          console.error('Unexpectedly encountered the ExtUnit extension constructor. Ignoring.');
          newAcc = acc;
          break;
      }
      return newAcc;
    },
    {
      totalAmulet: new BigNumber(0),
      totalUSDUnit: new BigNumber(0),
    }
  );

  if (totalAmulet.eq(0) && totalUSDUnit.eq(0)) {
    return undefined;
  } else if (totalUSDUnit.eq(0)) {
    // everything is in AmuletUnit
    return {
      totalCurrency: 'AmuletUnit',
      totalAmount: totalAmulet,
      currencyForAllReceivers: 'AmuletUnit',
    };
  } else if (totalAmulet.eq(0)) {
    // everything is in USDUnit
    return {
      totalCurrency: 'USDUnit',
      totalAmount: totalUSDUnit,
      currencyForAllReceivers: 'USDUnit',
    };
  } else {
    // both, we use AmuletUnit to show the total amount
    const totalAmount = totalUSDUnit.div(amuletPrice).plus(totalAmulet);
    return {
      totalCurrency: 'AmuletUnit',
      totalAmount,
      currencyForAllReceivers: 'AmuletUnit & USDUnit',
    };
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
        Send <AmountDisplay amount={BigNumber(amount.amount)} currency={amount.unit} /> to{' '}
      </Typography>
      <BftAnsEntry partyId={receiver} variant="h5" fontWeight="bold" className="payment-receiver" />
      <Stack direction="row" alignItems="center" spacing={1}>
        <Typography variant="body2">via</Typography>{' '}
        <BftAnsEntry partyId={provider} variant="body2" className="payment-provider" />
      </Stack>
    </Stack>
  );
};

interface MultipleRecipientsInfoProps {
  receiverAmounts: ReceiverAmount[];
  currencyForAllReceivers: Unit | BothUnits;
  amuletPrice: BigNumber;
  provider: string;
}

const MultiRecipientsInfo: React.FC<MultipleRecipientsInfoProps> = ({
  currencyForAllReceivers,
  receiverAmounts,
  amuletPrice,
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
        <BftAnsEntry partyId={provider} variant="body2" className="payment-provider" />
      </Stack>
      <Table>
        <TableBody>
          {receiverAmounts.map(({ amount: { amount, unit }, receiver }) => {
            const converted = convertCurrency(new BigNumber(amount), unit, amuletPrice);
            return (
              <TableRow key={receiver} id={`${receiver}-payment-row`}>
                <TableCell variant="party">
                  <BftAnsEntry partyId={receiver} variant="h6" className="receiver-entry" />
                </TableCell>
                <TableCell>
                  <Typography variant="h6" className="receiver-amount">
                    <AmountDisplay amount={BigNumber(amount)} currency={unit} />
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
  domainId?: string;
  total: Total;
  amuletPrice: BigNumber;
}
const TotalPaymentContainer: React.FC<PaymentContainerProps> = ({
  contractId,
  total,
  amuletPrice,
}) => {
  const converted = convertCurrency(total.totalAmount, total.totalCurrency, amuletPrice);
  const ccAmount = total.totalCurrency === 'AmuletUnit' ? total.totalAmount : converted.amount;

  const totalAmulet = ccAmount; // TODO (#878): compute actual fee
  const totalUSDUnit = totalAmulet.times(amuletPrice);

  return (
    <Container>
      <Box bgcolor="colors.neutral.20" border={1} borderColor="colors.neutral.30">
        <Stack alignItems="center" spacing={4} marginY={4}>
          <Typography variant="body1">{"You'll pay:"}</Typography>
          <Stack alignItems="center">
            <Typography variant="h5" className="payment-total-amulet">
              <AmountDisplay amount={totalAmulet} currency={'AmuletUnit'} />
            </Typography>
            <Typography variant="body2" className="payment-compute">
              <AmountDisplay amount={totalUSDUnit} currency={'USDUnit'} /> @{' '}
              <RateDisplay
                base={total.totalCurrency}
                quote={converted.currency}
                amuletPrice={amuletPrice}
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

const ConfirmPaymentButton: React.FC<{
  contractId: ContractId<payment.AppPaymentRequest>;
}> = ({ contractId }) => {
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
