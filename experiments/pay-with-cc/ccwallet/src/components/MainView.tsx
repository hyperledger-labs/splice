// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React from 'react';
import { Container, Divider, Grid, Header } from 'semantic-ui-react';
import PaymentRequestList from './PaymentRequestList';
import { Coin } from '@daml.js/canton-coin/lib/CC/Coin';
import { useLedger, useParty, useStreamQueries } from '@daml/react';
import { PaymentRequest, TransferWrapper } from '@daml.js/pay-with-cc/lib/PayWithCC';
import { CreateEvent } from '@daml/ledger';
import { CoinRules, Transfer } from '@daml.js/canton-coin/lib/CC/CoinRules';

const MainView: React.FC = () => {
  const party = useParty();
  const coins = useStreamQueries(Coin, (() => [{ owner: party }]));
  const requests = useStreamQueries(PaymentRequest, (() => [{user: party}]));
  const rules = useStreamQueries(CoinRules);
  const ledger = useLedger();

  const onApprove = async (request: CreateEvent<PaymentRequest>): Promise<boolean> => {
    if (!rules.loading && rules.contracts.length === 1) {
      const rule = rules.contracts[0].payload;
      try {
        const requiredQuantity = Number.parseFloat(request.payload.quantity) + Number.parseFloat(rule.config.updateFee.fee) * 2;
        const transfer: Transfer = {
          sender: party,
          inputs: coins.contracts.map(ev => ({tag: 'InputCoin', value: ev.contractId})),
          outputs: [
            {tag: 'OutputSenderCoin', value: {exactQuantity: requiredQuantity.toString(), lock: null}},
            {tag: 'OutputSenderCoin', value: {exactQuantity: null, lock: null}},
          ],
          payload: "split",
        };
        const [[c0]] = await ledger.createAndExercise(TransferWrapper.ExerciseTransfer, {p: party, svc: rule.svc}, {transfer: transfer});
        if (c0.tag === 'TransferResultCoin') {
          await ledger.exercise(PaymentRequest.PaymentRequest_Approve, request.contractId, {coin: c0.value});
          window.location.assign(request.payload.redirectUri);
          return true;
        } else {
          alert('Unexpected locked coin');
          return false;
        }
      } catch (e) {
        alert(`Error ${e}`);
        return false;
      }
    } else {
      alert("Failed to find CoinRules");
      return false;
    }
  };

  return (
    <Container>
      <Grid>
        <Grid.Row>
          <Grid.Column>
            <Grid>
              <Grid.Row columns="equal" verticalAlign="middle">
                <Grid.Column>
                  <Header as="h1">CC Wallet</Header>
                </Grid.Column>
              </Grid.Row>
            </Grid>
          </Grid.Column>
        </Grid.Row>
        <Divider />
        <Grid.Row>
          <Grid.Column>
            <Header as="h2">Payment Requests</Header>
            <PaymentRequestList
              requests={requests}
              onApprove={onApprove}
            />
          </Grid.Column>
        </Grid.Row>
        <Divider />
      </Grid>
    </Container>
  );
}

export default MainView;
