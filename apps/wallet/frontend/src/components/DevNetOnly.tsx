// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';

import useGetAmuletRules from '../hooks/scan-proxy/useGetAmuletRules';

const DevNetOnly: React.FC<{ children: React.ReactElement }> = props => {
  const { data: amuletRules, error } = useGetAmuletRules();

  if (error) {
    console.error('Failed to resolve isDevNet', error);
    return null;
  }

  const isDevNet = amuletRules?.contract.payload.isDevNet;

  if (!isDevNet) {
    return null;
  } else {
    return props.children;
  }
};

export default DevNetOnly;
