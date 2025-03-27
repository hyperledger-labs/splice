// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { AnsEntryDisplay, AnsEntryProps } from '@lfdecentralizedtrust/splice-common-frontend';
import React from 'react';

import useLookupAnsEntryByParty from '../hooks/scan-proxy/useLookupAnsEntryByParty';

const BftAnsEntry: React.FC<AnsEntryProps> = props => {
  const { partyId } = props;
  const { data: ansEntry, isLoading, isError } = useLookupAnsEntryByParty(partyId);

  if (isLoading || isError) {
    return <div>...</div>;
  } else {
    return <AnsEntryDisplay ansEntryName={ansEntry?.name} {...props} />;
  }
};

export default BftAnsEntry;
