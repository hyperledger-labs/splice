import { useEffect } from "react";
import { Contract } from "./Contract";

export const useInterval = (f: () => void, ms: number) => {
    useEffect(() => {
        const timer = setInterval(f, ms);
        return () => clearInterval(timer);
    }, [f, ms]);
};

export const sameContracts = <T, >(a: Contract<T>[], b: Contract<T>[]) : Boolean => {
    if (a.length !== b.length) {
        return false;
    }
    for (let i = 0; i < a.length; i++) {
        if (a[i].contractId !== b[i].contractId) {
            return false;
        }
    }
    return true;
}
