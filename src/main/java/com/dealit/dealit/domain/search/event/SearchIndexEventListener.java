package com.dealit.dealit.domain.search.event;

import com.dealit.dealit.domain.search.service.SearchIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexEventListener {

	private final SearchIndexService searchIndexService;

	@Async("searchIndexTaskExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handle(ProductSearchIndexRequestedEvent event) {
		runSafely("product index", event.productId(), () -> searchIndexService.indexRegularProduct(event.productId(), event.searchVersion()));
	}

	@Async("searchIndexTaskExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handle(ProductSearchDeleteRequestedEvent event) {
		runSafely("product delete", event.productId(), () -> searchIndexService.deleteRegularProduct(event.productId()));
	}

	@Async("searchIndexTaskExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handle(AuctionSearchIndexRequestedEvent event) {
		runSafely("auction index", event.auctionId(), () -> searchIndexService.indexAuction(event.auctionId()));
	}

	@Async("searchIndexTaskExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handle(AuctionSearchDeleteRequestedEvent event) {
		runSafely("auction delete", event.auctionId(), () -> searchIndexService.deleteAuction(event.auctionId()));
	}

	private void runSafely(String action, Long targetId, Runnable task) {
		try {
			task.run();
		} catch (RuntimeException exception) {
			log.warn("Failed to process search index event. action={}, targetId={}", action, targetId, exception);
		}
	}
}
