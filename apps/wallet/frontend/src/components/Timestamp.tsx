import React from 'react';

import * as damlTypes from '@daml/types';

const Timestamp: React.FC<{
  time: damlTypes.Time;
}> = ({ time }) => <>{new Date(+time / 1000).toLocaleString()}</>;

export default Timestamp;
