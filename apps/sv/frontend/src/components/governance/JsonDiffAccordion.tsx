// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { Accordion, AccordionDetails, AccordionSummary, Box, Typography } from '@mui/material';

export const JsonDiffAccordion = ({ children }: { children: React.ReactNode }): JSX.Element => {
  return (
    <Box>
      <Accordion elevation={0}>
        <AccordionSummary
          expandIcon={<ExpandMoreIcon />}
          aria-controls="json-diff-content"
          id="json-diff-header"
        >
          <Typography variant="h6">JSON Diffs</Typography>
        </AccordionSummary>
        <AccordionDetails data-testid="json-diffs-details">{children}</AccordionDetails>
      </Accordion>
    </Box>
  );
};
