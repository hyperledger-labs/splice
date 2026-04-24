// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Container, Stack, Table, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import Typography from '@mui/material/Typography';
import BftAnsEntry from './BftAnsEntry';
import { TransferLeg } from '@daml.js/splice-api-token-allocation-v2/lib/Splice/Api/Token/AllocationV2/module';
import MetaDisplay from './MetaDisplay';
import BigNumber from 'bignumber.js';

const TransferLegsDisplay: React.FC<{
  parentId: string;
  transferLegs: TransferLeg[];
  getActionButton: (transferLegId: string, parentComponentId: string) => React.ReactNode | null;
}> = ({ transferLegs, parentId, getActionButton }) => {
  const sortedLegs = [...transferLegs].sort((a, b) => a.transferLegId.localeCompare(b.transferLegId));
  return (
    <Container>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Id</TableCell>
            <TableCell>Sender</TableCell>
            <TableCell>Receiver</TableCell>
            <TableCell align="right">Amount</TableCell>
            <TableCell>Meta</TableCell>
            <TableCell />
          </TableRow>
        </TableHead>
        <TableBody>
          {sortedLegs.map(transferLeg => {
            const { meta, sender, receiver, instrumentId, amount, transferLegId } = transferLeg;
            const id = `transfer-leg-${parentId}-${transferLegId}`;
            return (
              <TableRow key={transferLegId} id={id} className="allocation-row">
                <TableCell>
                  <Typography variant="body2" className="allocation-legid">
                    {transferLegId}
                  </Typography>
                </TableCell>
                <TableCell>
                  <Stack>
                    <BftAnsEntry partyId={sender.owner} className="allocation-sender" />
                    {sender.provider ? (
                        <BftAnsEntry partyId={sender.provider} className="allocation-provider" />
                    ) : null}
                    {sender.id ? (
                      <Typography variant="caption" color="text.secondary">
                        Account: {sender.id}
                      </Typography>
                    ) : null}
                  </Stack>
                </TableCell>
                <TableCell>
                  <Stack>
                    <BftAnsEntry partyId={receiver.owner} className="allocation-receiver" />
                    {receiver.provider ? (
                      <Typography variant="caption" color="text.secondary">
                        Provider: {receiver.provider}
                      </Typography>
                    ) : null}
                    {receiver.id ? (
                      <Typography variant="caption" color="text.secondary">
                        Account: {receiver.id}
                      </Typography>
                    ) : null}
                  </Stack>
                </TableCell>
                <TableCell>
                  <Typography variant="body2" className="allocation-amount-instrument">
                    {BigNumber(amount).toFormat()} {instrumentId.id}
                  </Typography>
                </TableCell>
                <TableCell>
                  <MetaDisplay meta={meta.values} />
                </TableCell>
                <TableCell>{getActionButton(transferLegId, id)}</TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </Container>
  );
};

export default TransferLegsDisplay;
