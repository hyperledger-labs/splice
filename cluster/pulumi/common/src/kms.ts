import { GCP_PROJECT, GCP_ZONE } from 'splice-pulumi-common';
import { z } from 'zod';

export const KmsConfigSchema = z.object({
  type: z.string().default('gcp'),
  locationId: z.string().default(GCP_ZONE!),
  projectId: z.string().default(GCP_PROJECT),
  // The keyring must already exist; create it manually if necessary.
  // TODO(#18751): Try to remove this limitation by having Pulumi manage keyrings.
  keyRingId: z.string(),
});

export type KmsConfig = z.infer<typeof KmsConfigSchema>;
