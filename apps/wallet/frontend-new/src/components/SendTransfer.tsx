import React, { useEffect, useState } from 'react';

import {
  Box,
  Button,
  Card,
  CardContent,
  FormControl,
  InputAdornment,
  MenuItem,
  OutlinedInput,
  Select,
  Stack,
  TextField,
  Typography,
} from '@mui/material';

const SendTransfer: React.FC = () => {
  const [usd, setUsdAmount] = useState(1);
  const [cc, setCCAmount] = useState('1');
  const [expDays, setExpDays] = useState('1');

  const expiryOptions = [
    { name: '1 day', value: 1 },
    { name: '10 days', value: 10 },
    { name: '30 days', value: 30 },
    { name: '60 days', value: 60 },
  ];

  // Convert CC to USD
  useEffect(() => {
    setUsdAmount(parseFloat(cc) * 1.2);
  }, [cc, setUsdAmount]);

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Transfers
      </Typography>
      <Card variant="outlined" sx={{ marginBottom: '4px', backgroundColor: '#242F40' }}>
        <CardContent sx={{ paddingX: '64px' }}>
          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">Recipient</Typography>
            <TextField label="" variant="outlined" />
          </Stack>
          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">Amount</Typography>
            <Box display="flex">
              <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
                <OutlinedInput
                  value={cc}
                  onChange={e => setCCAmount(e.target.value)}
                  endAdornment={<InputAdornment position="end">CC</InputAdornment>}
                  aria-describedby="outlined-amount-cc-helper-text"
                  inputProps={{
                    'aria-label': 'amount',
                  }}
                />
              </FormControl>
              {/* Slight deviation from the original design here. The USD field is below the CC field in the figma designs */}
              <FormControl>
                <OutlinedInput
                  disabled
                  value={usd}
                  endAdornment={<InputAdornment position="end">USD</InputAdornment>}
                  aria-describedby="outlined-amount-usd-helper-text"
                  inputProps={{
                    'aria-label': 'amount',
                  }}
                />
              </FormControl>
            </Box>
          </Stack>
          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">Expiration</Typography>
            <FormControl fullWidth>
              <Select
                id="expiration-days-select"
                value={expDays}
                onChange={e => setExpDays(e.target.value)}
              >
                {expiryOptions.map((exp, index) => (
                  <MenuItem key={'exp-option-' + index} value={exp.value}>
                    {exp.name}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
          </Stack>
          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">
              Description <Typography variant="caption">(optional)</Typography>{' '}
            </Typography>
            <TextField rows={4} multiline />
          </Stack>
          <Button variant="contained" fullWidth size="large">
            Send
          </Button>
        </CardContent>
      </Card>
    </Stack>
  );
};
export default SendTransfer;
