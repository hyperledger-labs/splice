import React from 'react';

import useGetCoinRules from '../hooks/scan-proxy/useGetCoinRules';

const DevNetOnly: React.FC<{ children: React.ReactElement }> = props => {
  const { data: coinRules, error } = useGetCoinRules();

  if (error) {
    console.error('Failed to resolve isDevNet', error);
    return null;
  }

  const isDevNet = coinRules?.contract.payload.isDevNet;

  if (!isDevNet) {
    return null;
  } else {
    return props.children;
  }
};

export default DevNetOnly;
