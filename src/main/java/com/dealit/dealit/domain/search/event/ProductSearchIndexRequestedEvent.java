package com.dealit.dealit.domain.search.event;

public record ProductSearchIndexRequestedEvent(Long productId, long searchVersion) {
}
