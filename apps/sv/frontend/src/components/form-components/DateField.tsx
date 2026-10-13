// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useMemo } from 'react';
import { KeyboardArrowDown } from '@mui/icons-material';
import { Box, Typography } from '@mui/material';
import { DesktopDateTimePicker, LocalizationProvider } from '@mui/x-date-pickers';
import dayjs, { Dayjs } from 'dayjs';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import {
  CREATE_PROPOSAL_FIELD_HELPER_SX,
  CREATE_PROPOSAL_FIELD_LABEL_SX,
} from '../../constants/createProposalLayout';
import { useFieldContext } from '../../hooks/formContext';
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs';
import { datePickerFieldSx } from '../../themes/fieldStyles';
import { DATE_TIME_PLACEHOLDER } from '../../utils/constants';

export interface DateFieldProps {
  title?: string;
  description?: string;
  minDate?: Dayjs | null;
  id: string;
}

export const DateField: React.FC<DateFieldProps> = props => {
  const { title, description, minDate, id } = props;
  const field = useFieldContext<string>();

  const dateValue = useMemo(() => dayjs(field.state.value), [field.state.value]);

  return (
    <Box>
      {title && (
        <Typography component="p" sx={{ ...CREATE_PROPOSAL_FIELD_LABEL_SX, mb: 1 }}>
          {title}
        </Typography>
      )}

      {description && (
        <Typography component="p" sx={{ ...CREATE_PROPOSAL_FIELD_HELPER_SX, mb: 1 }}>
          {description}
        </Typography>
      )}

      <LocalizationProvider dateAdapter={AdapterDayjs}>
        <DesktopDateTimePicker
          value={dateValue}
          format={dateTimeFormatISO}
          minDateTime={minDate === null ? undefined : (minDate ?? dayjs())}
          ampm={false}
          onClose={() => field.handleBlur()}
          onChange={newDate => field.handleChange(newDate?.format(dateTimeFormatISO)!)}
          enableAccessibleFieldDOMStructure={false}
          slots={{
            openPickerIcon: KeyboardArrowDown,
          }}
          slotProps={{
            textField: {
              fullWidth: true,
              variant: 'outlined',
              id: `${id}-field`,
              error: !field.state.meta.isValid,
              helperText: field.state.meta.errors?.[0],
              onBlur: field.handleBlur,
              sx: datePickerFieldSx,
              inputProps: {
                'data-testid': `${id}-field`,
                placeholder: DATE_TIME_PLACEHOLDER,
              },
            },
            openPickerButton: {
              sx: {
                color: 'text.light',
                marginRight: 0,
                padding: 0,
                cursor: 'pointer',
                '& .MuiSvgIcon-root': {
                  fontSize: 16,
                },
              },
            },
          }}
        />
      </LocalizationProvider>
    </Box>
  );
};
