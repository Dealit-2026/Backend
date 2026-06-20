package com.dealit.dealit.domain.search.service;

import com.dealit.dealit.domain.auction.AuctionStatus;
import com.dealit.dealit.domain.auction.entity.Auction;
import com.dealit.dealit.domain.auction.repository.AuctionRepository;
import com.dealit.dealit.domain.product.ProductSaleType;
import com.dealit.dealit.domain.product.ProductStatus;
import com.dealit.dealit.domain.product.entity.Product;
import com.dealit.dealit.domain.product.repository.ProductRepository;
import com.dealit.dealit.domain.search.document.SearchDocument;
import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchIndexService {

	private final ProductRepository productRepository;
	private final AuctionRepository auctionRepository;
	private final OpenSearchClient openSearchClient;
	private final SearchDocumentFactory searchDocumentFactory;
	private final Clock clock;

	public void indexRegularProduct(Long productId) {
		productRepository.findByProductIdAndDeletedAtIsNull(productId)
			.ifPresentOrElse(this::indexRegularProductIfSearchable, () -> deleteRegularProduct(productId));
	}

	public void indexRegularProduct(Long productId, long eventSearchVersion) {
		productRepository.findByProductIdAndDeletedAtIsNull(productId)
			.ifPresentOrElse(
				product -> indexRegularProductIfCurrent(product, eventSearchVersion),
				() -> deleteRegularProduct(productId)
			);
	}

	public void deleteRegularProduct(Long productId) {
		openSearchClient.delete("REGULAR-" + productId);
	}

	public void indexAuction(Long auctionId) {
		auctionRepository.findDetailByAuctionIdAndDeletedAtIsNullAndProductDeletedAtIsNull(auctionId)
			.ifPresentOrElse(this::indexAuctionIfSearchable, () -> deleteAuction(auctionId));
	}

	public void deleteAuction(Long auctionId) {
		openSearchClient.delete("AUCTION-" + auctionId);
	}

	private void indexRegularProductIfSearchable(Product product) {
		if (product.getSaleType() != ProductSaleType.REGULAR || product.getStatus() != ProductStatus.ON_SALE) {
			deleteRegularProduct(product.getProductId());
			return;
		}
		SearchDocument document = searchDocumentFactory.regularProduct(product);
		openSearchClient.indexIfVersionNotOlder(document);
	}

	private void indexRegularProductIfCurrent(Product product, long eventSearchVersion) {
		if (product.getSearchVersion() > eventSearchVersion) {
			return;
		}
		indexRegularProductIfSearchable(product);
	}

	private void indexAuctionIfSearchable(Auction auction) {
		if (auction.getStatus() != AuctionStatus.ONGOING || !auction.getEndsAt().isAfter(OffsetDateTime.now(clock))) {
			deleteAuction(auction.getAuctionId());
			return;
		}
		SearchDocument document = searchDocumentFactory.auction(auction);
		openSearchClient.indexIfVersionNotOlder(document);
	}
}
