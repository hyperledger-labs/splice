// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useMemo } from 'react';
import { Box, Typography } from '@mui/material';
import { DesktopDateTimePicker, LocalizationProvider } from '@mui/x-date-pickers';
import dayjs, { Dayjs } from 'dayjs';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useFieldContext } from '../../hooks/formContext';
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs';

export interface DateFieldProps {
  title?: string;
  description?: string;
  minDate?: Dayjs;
  id: string;
}

export const DateField: React.FC<DateFieldProps> = props => {
  const { title, description, minDate, id } = props;
  const field = useFieldContext<string>();

  const dateValue = useMemo(() => dayjs(field.state.value), [field.state.value]);

  return (
    <Box>
      {title && (
        <Typography variant="h5" gutterBottom>
          {title}
        </Typography>
      )}

      {description && (
        <Typography variant="body2" color="text.secondary" gutterBottom>
          {description}
        </Typography>
      )}

      <LocalizationProvider dateAdapter={AdapterDayjs}>
        <DesktopDateTimePicker
          value={dateValue}
          format={dateTimeFormatISO}
          minDateTime={minDate || dayjs()}
          ampm={false}
          onChange={newDate => field.handleChange(newDate?.format(dateTimeFormatISO)!)}
          enableAccessibleFieldDOMStructure={false}
          slotProps={{
            textField: {
              fullWidth: true,
              variant: 'outlined',
              id: `${id}-field`,
              helperText: field.state.meta.errors?.[0],
              onBlur: field.handleBlur,
              inputProps: {
                'data-testid': `${id}-field`,
              },
            },
          }}
        />
      </LocalizationProvider>
    </Box>
  );
};
