import { CnsEntryDisplay, CnsEntryProps } from 'common-frontend';
import React from 'react';

import useLookupCnsEntryByParty from '../hooks/scan-proxy/useLookupCnsEntryByParty';

const BftCnsEntry: React.FC<CnsEntryProps> = props => {
  const { partyId } = props;
  const { data: cnsEntry, isLoading, isError } = useLookupCnsEntryByParty(partyId);

  if (isLoading || isError) {
    return <div>...</div>;
  } else {
    return <CnsEntryDisplay cnsEntry={cnsEntry} {...props} />;
  }
};

export default BftCnsEntry;
