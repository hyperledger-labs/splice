import * as React from 'react';
import { PropsWithChildren } from 'react';

import { Stack, Table, TableContainer, Typography } from '@mui/material';

export const TitledTable: React.FC<PropsWithChildren<{ title: string }>> = ({
  children,
  title,
}) => {
  return (
    <Stack spacing={2} marginTop={3}>
      <Typography variant="h4" fontWeight="bold">
        {title}
      </Typography>
      <TableContainer>
        <Table>{children}</Table>
      </TableContainer>
    </Stack>
  );
};

export default TitledTable;
