package com.dealit.dealit.domain.search;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;

import com.dealit.dealit.domain.search.event.ProductSearchIndexRequestedEvent;
import com.dealit.dealit.domain.search.event.SearchIndexEventListener;
import com.dealit.dealit.domain.search.service.SearchIndexService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchIndexEventListenerTest {

	@Test
	@DisplayName("OpenSearch 색인 실패는 이벤트 리스너 밖으로 전파되지 않는다")
	void indexingFailureDoesNotPropagate() {
		SearchIndexService searchIndexService = org.mockito.Mockito.mock(SearchIndexService.class);
		SearchIndexEventListener listener = new SearchIndexEventListener(searchIndexService);
		ProductSearchIndexRequestedEvent event = new ProductSearchIndexRequestedEvent(1L, 2L);
		doThrow(new IllegalStateException("OpenSearch failed"))
			.when(searchIndexService)
			.indexRegularProduct(1L, 2L);

		assertThatCode(() -> listener.handle(event)).doesNotThrowAnyException();
	}
}
