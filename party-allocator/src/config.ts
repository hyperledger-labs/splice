import z from "zod";

const partyAllocationsSchema = z.object({
  token: z.string(),
  jsonLedgerApiUrl: z.string(),
  scanApiUrl: z.string(),
  validatorApiUrl: z.string(),
  maxParties: z.number(),
  keyDirectory: z.string(),
  parallelism: z.number().default(20),
});

type PartyAllocationsConf = z.infer<typeof partyAllocationsSchema>;

export const config: PartyAllocationsConf = partyAllocationsSchema.parse(
  JSON.parse(process.env.EXTERNAL_CONFIG!),
);
