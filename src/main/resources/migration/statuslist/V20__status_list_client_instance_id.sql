ALTER TABLE status_list_entry
    ADD COLUMN client_instance_id UUID;

CREATE UNIQUE INDEX status_list_entry_client_instance_id ON status_list_entry (client_instance_id);

