// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { ReactNode } from 'react';

import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { Stack } from '@mui/material';
import Accordion from '@mui/material/Accordion';
import AccordionDetails from '@mui/material/AccordionDetails';
import AccordionSummary from '@mui/material/AccordionSummary';
import Typography from '@mui/material/Typography';

interface AccordionItemProps {
  title: React.ReactNode;
  content: React.ReactNode;
  initiallyOpen: boolean;
}

const AccordionItem: React.FC<AccordionItemProps> = ({ title, content, initiallyOpen }) => {
  const [expanded, setExpanded] = React.useState<boolean>(initiallyOpen);

  const handleChange = (isExpanded: boolean) => {
    setExpanded(isExpanded);
  };

  return (
    <Accordion expanded={expanded} onChange={(_e, isExpanded) => handleChange(isExpanded)}>
      <AccordionSummary
        expandIcon={<ExpandMoreIcon />}
        aria-controls={`${title}-content`}
        id={`${title}-header`}
      >
        <Typography>{title}</Typography>
      </AccordionSummary>
      <AccordionDetails id={'accordion-details'}>{content}</AccordionDetails>
    </Accordion>
  );
};

export interface AccordionListProps {
  unfoldedAccordions: { title: React.ReactNode; content: React.ReactNode }[];
  foldedAccordions: { title: React.ReactNode; content: React.ReactNode }[];
}

const AccordionList: React.FC<AccordionListProps> = ({ unfoldedAccordions, foldedAccordions }) => {
  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h5">Config diffs</Typography>
      {unfoldedAccordions.map((accordion, index) => (
        <AccordionItem
          key={`unfolded-${index}`}
          title={
            <ComponentHeaderWrapper header={'Comparing against effective configuration'}>
              {accordion.title}
            </ComponentHeaderWrapper>
          }
          content={accordion.content}
          initiallyOpen
        />
      ))}
      {foldedAccordions.map((accordion, index) => (
        <AccordionItem
          key={`folded-${index}`}
          title={
            <ComponentHeaderWrapper header={'Comparing against in-flight proposal'}>
              {accordion.title}
            </ComponentHeaderWrapper>
          }
          content={accordion.content}
          initiallyOpen={false}
          data-testid={'folded-accordion'}
        />
      ))}
    </Stack>
  );
};

const ComponentHeaderWrapper: React.FC<{ header: string; children: ReactNode }> = ({
  header,
  children,
}) => {
  return (
    <>
      {header}: {children}
    </>
  );
};

export default AccordionList;
