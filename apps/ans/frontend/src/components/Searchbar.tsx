// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Search } from '@mui/icons-material';
import { InputBase, styled, InputBaseProps, InputAdornment } from '@mui/material';

import { useAnsConfig } from '../utils';

const SearchbarStyled = styled(InputBase)<InputBaseProps>(({ theme }) => ({
  border: `1px solid ${theme.palette.colors.neutral[15]}`,
  borderRadius: '4px',
  padding: theme.spacing(2),
  backgroundColor: theme.palette.colors.neutral[15],
  '& .MuiInputBase-input': {
    padding: 0,
  },
}));

const Searchbar: React.FC<InputBaseProps> = props => {
  const config = useAnsConfig();
  return (
    <SearchbarStyled
      startAdornment={
        <InputAdornment position="start">
          <Search color="secondary" />
        </InputAdornment>
      }
      endAdornment={
        <InputAdornment position="end">
          .unverified.{config.spliceInstanceNames.nameServiceNameAcronym.toLowerCase()}
        </InputAdornment>
      }
      {...props}
    />
  );
};

export default Searchbar;
