package com.dealit.dealit.domain.search;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dealit.dealit.domain.auction.repository.AuctionRepository;
import com.dealit.dealit.domain.auction.AuctionStatus;
import com.dealit.dealit.domain.auction.entity.Auction;
import com.dealit.dealit.domain.product.ProductSaleType;
import com.dealit.dealit.domain.product.ProductStatus;
import com.dealit.dealit.domain.product.entity.Product;
import com.dealit.dealit.domain.product.repository.ProductRepository;
import com.dealit.dealit.domain.search.document.SearchDocument;
import com.dealit.dealit.domain.search.dto.SearchResultType;
import com.dealit.dealit.domain.search.service.OpenSearchClient;
import com.dealit.dealit.domain.search.service.SearchDocumentFactory;
import com.dealit.dealit.domain.search.service.SearchIndexService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SearchIndexServiceTest {

	@Mock
	private ProductRepository productRepository;

	@Mock
	private AuctionRepository auctionRepository;

	@Mock
	private OpenSearchClient openSearchClient;

	@Mock
	private SearchDocumentFactory searchDocumentFactory;

	private SearchIndexService searchIndexService;

	@BeforeEach
	void setUp() {
		searchIndexService = new SearchIndexService(
			productRepository,
			auctionRepository,
			openSearchClient,
			searchDocumentFactory,
			Clock.fixed(Instant.parse("2026-06-20T00:00:00Z"), ZoneOffset.UTC)
		);
	}

	@Test
	@DisplayName("낮은 searchVersion 이벤트는 최신 상품 문서를 덮어쓰지 못한다")
	void staleEventIsSkipped() {
		Product product = createProduct(10L);
		increaseSearchVersionTo(product, 11L);
		when(productRepository.findByProductIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(product));

		searchIndexService.indexRegularProduct(10L, 10L);

		verify(searchDocumentFactory, never()).regularProduct(product);
		verify(openSearchClient, never()).indexIfVersionNotOlder(org.mockito.ArgumentMatchers.any(SearchDocument.class));
	}

	@Test
	@DisplayName("현재 searchVersion 이벤트는 OpenSearch에 version 비교 색인된다")
	void currentEventIsIndexedWithVersionGuard() {
		Product product = createProduct(10L);
		increaseSearchVersionTo(product, 11L);
		SearchDocument document = searchDocument(10L, 11L);
		when(productRepository.findByProductIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(product));
		when(searchDocumentFactory.regularProduct(product)).thenReturn(document);

		searchIndexService.indexRegularProduct(10L, 11L);

		verify(openSearchClient).indexIfVersionNotOlder(document);
	}

	@Test
	@DisplayName("같은 searchVersion 이벤트 재처리는 version 비교 색인으로 반복되어 멱등적이다")
	void sameEventCanBeRetriedIdempotently() {
		Product product = createProduct(10L);
		increaseSearchVersionTo(product, 11L);
		SearchDocument document = searchDocument(10L, 11L);
		when(productRepository.findByProductIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(product));
		when(searchDocumentFactory.regularProduct(product)).thenReturn(document);

		searchIndexService.indexRegularProduct(10L, 11L);
		searchIndexService.indexRegularProduct(10L, 11L);

		verify(openSearchClient, org.mockito.Mockito.times(2)).indexIfVersionNotOlder(eq(document));
	}

	@Test
	@DisplayName("낮은 searchVersion 경매 이벤트는 최신 경매 문서를 덮어쓰지 못한다")
	void staleAuctionEventIsSkipped() {
		Auction auction = createAuction(20L);
		increaseAuctionSearchVersionTo(auction, 11L);
		when(auctionRepository.findDetailByAuctionIdAndDeletedAtIsNullAndProductDeletedAtIsNull(20L))
			.thenReturn(Optional.of(auction));

		searchIndexService.indexAuction(20L, 10L);

		verify(searchDocumentFactory, never()).auction(auction);
		verify(openSearchClient, never()).indexIfVersionNotOlder(org.mockito.ArgumentMatchers.any(SearchDocument.class));
	}

	@Test
	@DisplayName("현재 searchVersion 경매 이벤트는 OpenSearch에 version 비교 색인된다")
	void currentAuctionEventIsIndexedWithVersionGuard() {
		Auction auction = createAuction(20L);
		increaseAuctionSearchVersionTo(auction, 11L);
		SearchDocument document = auctionSearchDocument(20L, 11L);
		when(auctionRepository.findDetailByAuctionIdAndDeletedAtIsNullAndProductDeletedAtIsNull(20L))
			.thenReturn(Optional.of(auction));
		when(searchDocumentFactory.auction(auction)).thenReturn(document);

		searchIndexService.indexAuction(20L, 11L);

		verify(openSearchClient).indexIfVersionNotOlder(document);
	}

	private Product createProduct(Long productId) {
		Product product = Product.create(
			"product",
			"description",
			ProductSaleType.REGULAR,
			19L,
			1L,
			BigDecimal.valueOf(10000),
			false,
			"Seoul",
			null,
			ProductStatus.ON_SALE
		);
		ReflectionTestUtils.setField(product, "productId", productId);
		return product;
	}

	private Auction createAuction(Long auctionId) {
		Product product = Product.create(
			"auction product",
			"description",
			ProductSaleType.AUCTION,
			19L,
			1L,
			BigDecimal.valueOf(10000),
			false,
			"Seoul",
			null,
			ProductStatus.ON_SALE
		);
		ReflectionTestUtils.setField(product, "productId", 100L);
		Auction auction = Auction.create(
			product,
			BigDecimal.valueOf(10000),
			BigDecimal.valueOf(1000),
			java.time.OffsetDateTime.parse("2026-06-20T00:00:00Z"),
			java.time.OffsetDateTime.parse("2026-06-21T00:00:00Z"),
			AuctionStatus.ONGOING
		);
		ReflectionTestUtils.setField(auction, "auctionId", auctionId);
		return auction;
	}

	private void increaseSearchVersionTo(Product product, long targetVersion) {
		while (product.getSearchVersion() < targetVersion) {
			product.increaseSearchVersion();
		}
	}

	private void increaseAuctionSearchVersionTo(Auction auction, long targetVersion) {
		while (auction.getSearchVersion() < targetVersion) {
			auction.increaseSearchVersion();
		}
	}

	private SearchDocument searchDocument(Long productId, long searchVersion) {
		return new SearchDocument(
			"REGULAR-" + productId,
			SearchResultType.REGULAR,
			productId,
			null,
			"product",
			"description",
			null,
			19L,
			List.of(19L),
			List.of("category"),
			BigDecimal.valueOf(10000),
			null,
			"Seoul",
			ProductStatus.ON_SALE,
			null,
			null,
			0L,
			0L,
			searchVersion,
			null
		);
	}

	private SearchDocument auctionSearchDocument(Long auctionId, long searchVersion) {
		return new SearchDocument(
			"AUCTION-" + auctionId,
			SearchResultType.AUCTION,
			100L,
			auctionId,
			"auction product",
			"description",
			null,
			19L,
			List.of(19L),
			List.of("category"),
			null,
			BigDecimal.valueOf(10000),
			"Seoul",
			ProductStatus.ON_SALE,
			AuctionStatus.ONGOING,
			null,
			0L,
			0L,
			searchVersion,
			null
		);
	}
}
