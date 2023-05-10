import { IntervalDisplay } from 'common-frontend';
import DateDisplay from 'common-frontend/lib/components/DateDisplay';
import React, { useMemo, useState } from 'react';

import { Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';

import { DirectoryEntryContext } from '@daml.js/directory/lib/CN/Directory/module';
import {
  Subscription,
  SubscriptionContext,
  SubscriptionPayData,
} from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';
import { ContractId } from '@daml/types';

import { EntryWithPayData, useDirectoryUiState } from '../contexts/DirectoryContext';

const DirectoryEntries: React.FC = () => {
  const { subscriptionPayData, subscriptionWithEntryContextCid, entryWithContextCid } =
    useDirectoryUiState();
  const [entriesData, setEntriesData] = useState<EntryWithPayData[]>([]);

  const subToPayData = useMemo(() => {
    return new Map<ContractId<Subscription>, SubscriptionPayData>(
      subscriptionPayData.map(o => [o.contractId, o.payData])
    );
  }, [subscriptionPayData]);

  const subContextToSub = useMemo(() => {
    return new Map<ContractId<SubscriptionContext>, ContractId<Subscription>>(
      subscriptionWithEntryContextCid.map(s => [s.entryContextContractId, s.subContractId])
    );
  }, [subscriptionWithEntryContextCid]);

  useMemo(() => {
    const data: EntryWithPayData[] = entryWithContextCid.map(ec => {
      const subContractId = ec.contextContractId
        ? subContextToSub.get(
            DirectoryEntryContext.toInterface(SubscriptionContext, ec.contextContractId)
          )
        : undefined;
      const paymentData = subContractId ? subToPayData.get(subContractId) : undefined;

      return {
        contractId: ec.entry.contractId,
        expiresAt: ec.entry.payload.expiresAt,
        entryName: ec.entry.payload.name,
        amount: paymentData ? paymentData.paymentAmount.amount : '...',
        currency: paymentData ? paymentData.paymentAmount.currency : '...',
        paymentInterval: paymentData ? paymentData.paymentInterval.microseconds : '...',
        paymentDuration: paymentData ? paymentData.paymentDuration.microseconds : '...',
      };
    });
    setEntriesData(data);
  }, [entryWithContextCid, subContextToSub, subToPayData]);

  return (
    <div>
      <Typography variant="h5">Your Directory Entries</Typography>
      <Table sx={{ marginTop: '16px' }}>
        <TableHead>
          <TableRow>
            <TableCell>Name</TableCell>
            <TableCell>Amount</TableCell>
            <TableCell>Currency</TableCell>
            <TableCell>ExpiresAt</TableCell>
            <TableCell>Interval</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {entriesData.map(entry => (
            <TableRow key={entry.contractId} className="entries-table-row">
              <TableCell className="entries-table-name">{entry.entryName}</TableCell>
              <TableCell className="entries-table-amount">{entry.amount}</TableCell>
              <TableCell className="entries-table-currency">{entry.currency}</TableCell>
              <TableCell className="entries-table-expires-at">
                <DateDisplay datetime={entry.expiresAt} />
              </TableCell>
              <TableCell className="entries-table-payment-interval">
                <IntervalDisplay microseconds={entry.paymentInterval} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
};

export default DirectoryEntries;
