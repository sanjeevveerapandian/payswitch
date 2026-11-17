-- GET /recon/entries reads processed_requests by time window, in time order.
CREATE INDEX idx_processed_requests_created ON processed_requests (created_at, txn_id, op_type);
