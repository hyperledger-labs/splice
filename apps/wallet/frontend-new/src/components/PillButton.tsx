import { Button, styled } from '@mui/material';

const PillButton = styled(Button)({
  // most straightforward way of making "pill" buttons:
  // https://stackoverflow.com/questions/31617136/avoid-elliptical-shape-in-css-border-radius:
  borderRadius: '9999px',
  backgroundColor: '#577ff1',
  color: 'white',
});

export default PillButton;
