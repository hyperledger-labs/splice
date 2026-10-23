// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DsoInfo, SvInfo, theme } from '@canton-network/splice-common-frontend';
import { Contract } from '@canton-network/splice-common-frontend-utils';
import { dsoInfo } from '@canton-network/splice-common-test-handlers';
import { QueryClient, QueryClientProvider, onlineManager, useQuery } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import React from 'react';
import { CometBftNodeDumpOrErrorResponse } from '@canton-network/sv-openapi';
import { afterEach, describe, expect, test } from 'vitest';

import { ThemeProvider } from '@mui/material';

import { AmuletRules } from '@daml.js/splice-amulet/lib/Splice/AmuletRules';
import { DsoRules } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';

import DsoViewPrettyJSON from '../components/Dso';

const makeSvInfo = (): SvInfo => ({
  svUser: dsoInfo.sv_user,
  svPartyId: dsoInfo.sv_party_id,
});

const makeDsoInfo = (): DsoInfo => ({
  dsoPartyId: dsoInfo.dso_party_id,
  votingThreshold: BigInt(dsoInfo.voting_threshold),
  amuletRules: Contract.decodeOpenAPI(dsoInfo.amulet_rules.contract, AmuletRules),
  dsoRules: Contract.decodeOpenAPI(dsoInfo.dso_rules.contract, DsoRules),
  nodeStates: [],
});

const TestDso: React.FC = () => {
  const dsoInfoQuery = useQuery({
    queryKey: ['dsoInfo'],
    queryFn: async () => makeDsoInfo(),
    initialData: makeDsoInfo(),
  });
  const svInfoQuery = useQuery({
    queryKey: ['svInfo'],
    queryFn: async () => makeSvInfo(),
    initialData: makeSvInfo(),
  });
  const cometBftNodeDebugQuery = useQuery<CometBftNodeDumpOrErrorResponse>({
    queryKey: ['cometBftDebug'],
    queryFn: async () => {
      throw new Error('unreachable: query is paused while offline');
    },
  });
  return (
    <DsoViewPrettyJSON
      dsoInfoQuery={dsoInfoQuery}
      svInfoQuery={svInfoQuery}
      cometBftNodeDebugQuery={cometBftNodeDebugQuery}
    />
  );
};

describe('DsoViewPrettyJSON', () => {
  afterEach(() => {
    onlineManager.setOnline(true);
  });

  // With CantonBFT the cometbft debug endpoint 404s forever, so the query never
  // reaches success; when it pauses (browser offline / tab backgrounded during
  // retry backoff), status is 'pending' but isLoading is false and data is
  // undefined. Rendering must not crash in that state.
  test('does not crash when the cometBFT debug query is paused without data', () => {
    window.splice_config = {
      spliceInstanceNames: { amuletName: 'Amulet' },
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } as any;
    onlineManager.setOnline(false);

    render(
      <ThemeProvider theme={theme}>
        <QueryClientProvider client={new QueryClient()}>
          <TestDso />
        </QueryClientProvider>
      </ThemeProvider>
    );

    expect(screen.getByText('Super Validator Information')).toBeDefined();
  });
});
