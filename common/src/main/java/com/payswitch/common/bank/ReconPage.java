package com.payswitch.common.bank;

import java.util.List;

/**
 * One page of GET /recon/entries.
 *
 * @param hasMore true if asking for page + 1 would return more entries
 */
public record ReconPage(List<ReconEntry> entries, int page, int size, boolean hasMore) {
}
