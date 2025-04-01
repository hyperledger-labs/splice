// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AnsFieldProps,
  BaseAnsField,
  UserInput,
} from '@lfdecentralizedtrust/splice-common-frontend';
import React from 'react';

import useListAnsEntries from '../hooks/scan-proxy/useListAnsEntries';
import useLookupAnsEntryByName from '../hooks/scan-proxy/useLookupAnsEntryByName';

const BftAnsField: React.FC<AnsFieldProps> = props => {
  const [userInput, setUserInput] = React.useState<UserInput>({ type: 'typed', value: '' });
  const ansEntriesQuery = useListAnsEntries(20, userInput.value);
  const ansEntryQuery = useLookupAnsEntryByName(userInput.value, userInput.type === 'typed');

  return (
    <BaseAnsField
      {...props}
      userInput={userInput}
      updateUserInput={setUserInput}
      ansEntries={ansEntriesQuery}
      ansEntry={ansEntryQuery}
    />
  );
};

export default BftAnsField;
