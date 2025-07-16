// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Container, Table, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import Typography from '@mui/material/Typography';
import BftAnsEntry from './BftAnsEntry';
import { TransferLeg } from '@daml.js/splice-api-token-allocation/lib/Splice/Api/Token/AllocationV1/module';
import MetaDisplay from './MetaDisplay';
import BigNumber from 'bignumber.js';

const TransferLegsDisplay: React.FC<{
  parentId: string;
  transferLegs: { [transferLegId: string]: TransferLeg };
  getActionButton: (transferLegId: string, parentComponentId: string) => React.ReactNode | null;
}> = ({ transferLegs, parentId, getActionButton }) => {
  const ids = Object.keys(transferLegs).toSorted();
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
          {ids.map(transferLegId => {
            const { meta, sender, receiver, instrumentId, amount } = transferLegs[transferLegId];
            const id = `transfer-leg-${parentId}-${transferLegId}`;
            return (
              <TableRow key={transferLegId} id={id} className="allocation-row">
                <TableCell>
                  <Typography variant="body2" className="allocation-legid">
                    {transferLegId}
                  </Typography>
                </TableCell>
                <TableCell>
                  <BftAnsEntry partyId={sender} className="allocation-sender" />
                </TableCell>
                <TableCell>
                  <BftAnsEntry partyId={receiver} className="allocation-receiver" />
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
