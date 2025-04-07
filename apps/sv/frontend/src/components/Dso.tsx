// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { SvClientProvider, DsoViewPrettyJSON } from '@lfdecentralizedtrust/splice-common-frontend';
import React from 'react';
import 'react-json-pretty/themes/monikai.css';

import { useDsoInfos } from '../contexts/SvContext';
import { useCometBftDebug } from '../hooks/useCometBftDebug';
import { useMediatorStatus } from '../hooks/useMediatorStatus';
import { useSequencerStatus } from '../hooks/useSequencerStatus';
import { useSvConfig } from '../utils';

const DsoWithContexts: React.FC = () => {
  const config = useSvConfig();
  const WithProvider = () => {
    const dsoInfoQuery = useDsoInfos();
    const cometBftNodeDebugQuery = useCometBftDebug();
    const sequencerStatusQuery = useSequencerStatus();
    const mediatorStatusQuery = useMediatorStatus();
    return (
      <DsoViewPrettyJSON
        dsoInfoQuery={dsoInfoQuery}
        cometBftNodeDebugQuery={cometBftNodeDebugQuery}
        sequencerStatusQuery={sequencerStatusQuery}
        mediatorStatusQuery={mediatorStatusQuery}
      />
    );
  };
  return (
    <SvClientProvider url={config.services.sv.url}>
      <WithProvider />
    </SvClientProvider>
  );
};

export default DsoWithContexts;
