// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React from "react";
import { Form, List, Button } from "semantic-ui-react";

type Props = {
    onRequestEntry: (payload: string) => Promise<boolean>;
    price: string,
};

const EntryForm: React.FC<Props> = ({
    onRequestEntry, price
}) => {
    const [newPayload, setNewPayload] = React.useState<string | undefined>(undefined);
    const [isSubmitting, setIsSubmitting] = React.useState(false);

    const requestEntry = async (event?: React.FormEvent) => {
        if (event) {
            event.preventDefault();
        }
        setIsSubmitting(true);
        const success = await onRequestEntry(newPayload ?? "");
        setIsSubmitting(false);
        if (success) {
            setNewPayload(undefined);
        }
    };

    return (
        <List relaxed>
            <Form onSubmit={requestEntry}>
                <Form.Input
                    fluid
                    label='Payload'
                    placeholder='Payload'
                    readOnly={isSubmitting}
                    loading={isSubmitting}
                    value={newPayload ?? ""}
                    onChange={(_event, { value }) => setNewPayload(value?.toString())}
                    />
                <Button type="submit">
                    Request entry for {price} CC
                </Button>
            </Form>
        </List>
    );
};

export default EntryForm;
