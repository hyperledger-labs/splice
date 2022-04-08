// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React from 'react';
import { Container, Divider, Grid, Header } from 'semantic-ui-react';
import CoinList from './CoinList';
import { Coin, LockedCoin } from '@daml.js/canton-coin/lib/CC/Coin';
import { useParty, useStreamQueries } from '@daml/react';
import { OpenMiningRound } from '@daml.js/canton-coin/lib/CC/Round';
import LockedCoinList from './LockedCoinList';

const getCurrentRound = (openRounds: OpenMiningRound[]): OpenMiningRound | undefined => {
  const sortedRounds = openRounds.sort((x, y) => (parseInt(y.round.number) - parseInt(x.round.number)));
  return sortedRounds[0];
}

const MainView: React.FC = () => {
  const party = useParty();
  const coins = useStreamQueries(Coin, (() => [{ owner: party }]));
  const lockedCoins = useStreamQueries(LockedCoin, (() => [{ coin: { owner: party }}]));
  const openRounds = useStreamQueries(OpenMiningRound);
  const currentRound = getCurrentRound(openRounds.contracts.map(x => x.payload));

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
                <Grid.Column textAlign="right">
                  Current round: {currentRound?.round.number} @ {currentRound?.coinPrice} $/CC
                </Grid.Column>
              </Grid.Row>
            </Grid>
          </Grid.Column>
        </Grid.Row>
        <Divider />
        <Grid.Row>
          <Grid.Column>
            <Header as="h2">Coins</Header>
            <CoinList
              coins={coins}
              currentRound={currentRound}
            />
          </Grid.Column>
        </Grid.Row>
        <Divider />
        <Grid.Row>
          <Grid.Column>
            <Header as="h2">Locked coins</Header>
            <LockedCoinList
              lockedCoins={lockedCoins}
              currentRound={currentRound}
            />
          </Grid.Column>
        </Grid.Row>
      </Grid>
    </Container>
  );
}

export default MainView;
