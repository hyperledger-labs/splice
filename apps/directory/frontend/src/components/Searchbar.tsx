import { Search } from '@mui/icons-material';
import { InputBase, styled, InputBaseProps, InputAdornment } from '@mui/material';

const SearchbarStyled = styled(InputBase)<InputBaseProps>(({ theme }) => ({
  border: `1px solid ${theme.palette.colors.neutral[15]}`,
  borderRadius: '4px',
  padding: theme.spacing(2),
  backgroundColor: theme.palette.colors.neutral[15],
  '& .MuiInputBase-input': {
    padding: 0,
  },
}));

const Searchbar: React.FC<InputBaseProps> = props => (
  <SearchbarStyled
    startAdornment={
      <InputAdornment position="start">
        <Search />
      </InputAdornment>
    }
    {...props}
  />
);

export default Searchbar;
