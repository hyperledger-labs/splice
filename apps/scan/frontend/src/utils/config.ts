import { ConfigReader, serviceSchema } from 'common-frontend';
import { z } from 'zod';

type ScanServicesConfig = {
  scan: z.infer<typeof serviceSchema>;
};

type ScanConfig = {
  services: ScanServicesConfig;
};

const reader = new ConfigReader(
  z.object({
    services: z.object({
      scan: serviceSchema,
    }),
  })
);

export const config: ScanConfig = reader.loadConfig();
