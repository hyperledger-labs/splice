import React, { useContext } from "react";
import { SplitwiseServiceClient } from "./com/daml/network/splitwise/v0/Splitwise_serviceServiceClientPb";

const SplitwiseContext = React.createContext<SplitwiseServiceClient | undefined>(undefined);

export interface SplitwiseProps {
    url: string
};

export const SplitwiseClientProvider: React.FC<React.PropsWithChildren<SplitwiseProps>> = ({ url, children }) => {
    const splitwiseClient = new SplitwiseServiceClient(url, null, null);
    return <SplitwiseContext.Provider value={splitwiseClient}>{children}</SplitwiseContext.Provider>;
};

export const useSplitwiseClient: () => SplitwiseServiceClient = () => {
    const client = useContext<SplitwiseServiceClient | undefined>(SplitwiseContext);
    if (!client) {
        throw new Error("Splitwise client not initialized");
    }
    return client;
};
