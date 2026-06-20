package com.dealit.dealit.domain.search.event;

public record AuctionSearchIndexRequestedEvent(Long auctionId, long searchVersion) {
}
