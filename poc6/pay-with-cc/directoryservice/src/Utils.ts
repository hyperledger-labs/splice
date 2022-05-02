import { ContractId, Party } from "@daml/types";

export const shortenHash = (s: string): string => {
    const maxHashLength = 12;
    return s.length > maxHashLength ? `${s.substring(0, maxHashLength)}…` : s;
};

export const shortenContractId = <T,>(cid: ContractId<T>): string => shortenHash(cid);

export const shortenParty = (p: Party): string => {
    const parts = p.split("::");
    return parts.length === 2
      ? `${parts[0]}::${shortenHash(parts[1])}`
      : shortenHash(p);
};
