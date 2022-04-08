import { OpenMiningRound } from "@daml.js/canton-coin/lib/CC/Round";
import { ExpiringQuantity } from "@daml.js/canton-coin/lib/OpenBusiness/Fees";
import { ContractId, Party } from "@daml/types";

// One new cycle every 2.5min.
export const CYCLE_FREQUENCY = 2.5 * 60 * 1000;

export const getCurrentValue = (round: OpenMiningRound, quantity: ExpiringQuantity): number => {
    const roundsPassed = Math.min(0, parseInt(quantity.createdAt.number) - parseInt(round.round.number));
    const scaledRate =
        Number.parseFloat(quantity.ratePerRound.rate)
        / Number.parseFloat(round.coinPrice);
    const fees = scaledRate * roundsPassed;
    return Math.max(0.0, Number.parseFloat(quantity.initialQuantity) - fees);
};

export const ccToUsd = (round: OpenMiningRound, cc: number): number =>
    cc * Number.parseFloat(round.coinPrice);


export const getExpiresIn = (round: OpenMiningRound, quantity: ExpiringQuantity): number => {
    const scaledRate =
        Number.parseFloat(quantity.ratePerRound.rate) / Number.parseFloat(round.coinPrice);
    const lifetime = Math.ceil(Number.parseFloat(quantity.initialQuantity) / scaledRate);
    return Math.max(0,
        parseInt(quantity.createdAt.number) + lifetime - parseInt(round.round.number));

};

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