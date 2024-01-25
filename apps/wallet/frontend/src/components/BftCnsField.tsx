import { CnsFieldProps, BaseCnsField, UserInput } from 'common-frontend';
import React from 'react';

import useListCnsEntries from '../hooks/scan-proxy/useListCnsEntries';
import useLookupCnsEntryByName from '../hooks/scan-proxy/useLookupCnsEntryByName';

const BftCnsField: React.FC<CnsFieldProps> = props => {
  const [userInput, setUserInput] = React.useState<UserInput>({ type: 'typed', value: '' });
  const cnsEntriesQuery = useListCnsEntries(20, userInput.value);
  const cnsEntryQuery = useLookupCnsEntryByName(userInput.value, userInput.type === 'typed');

  return (
    <BaseCnsField
      {...props}
      userInput={userInput}
      updateUserInput={setUserInput}
      cnsEntries={cnsEntriesQuery}
      cnsEntry={cnsEntryQuery}
    />
  );
};

export default BftCnsField;
