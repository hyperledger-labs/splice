// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useDsoInfos } from '../contexts/SvContext';

export const useNetworkInstanceName: () => string | undefined = () => {
  const dsoInfosQuery = useDsoInfos();

  const scanUrls = dsoInfosQuery.data?.nodeStates.flatMap(nsContract => {
    return nsContract.payload.state.synchronizerNodes
      .entriesArray()
      .map(entry => entry[1].scan?.publicUrl)
      .filter(url => url !== undefined);
  }) as string[];

  if (scanUrls === undefined) {
    return undefined;
  }

  const instances = scanUrls
    .map(url => {
      const regex = /(?<=\/\/(?:scan\.)sv-\d+\.)([a-zA-Z0-9-]+)/;

      return url.match(regex)?.[1];
    })
    .filter(i => i !== undefined) as string[];

  if (instances.length > 0 && instances.every(i => i === instances[0])) {
    return getNetworkName(instances[0]);
  }

  return undefined;
};

const getNetworkName = (network: string) => {
  let networkName;

  // NOTE: mainnet does not have the network/cluster name in the url.
  if (network === 'global') {
    networkName = 'MainNet';
  } else if (network === 'test') {
    networkName = 'TestNet';
  } else if (network === 'dev') {
    networkName = 'DevNet';
  } else if (network?.startsWith('scratch')) {
    networkName = 'ScratchNet';
  }

  return networkName;
};
