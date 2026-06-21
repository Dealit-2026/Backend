package com.dealit.dealit.domain.search.document;

import com.dealit.dealit.domain.auction.AuctionStatus;
import com.dealit.dealit.domain.product.ProductStatus;
import com.dealit.dealit.domain.search.dto.SearchResultType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record SearchDocument(
	String id,
	SearchResultType type,
	Long productId,
	Long auctionId,
	String name,
	String description,
	String thumbnailUrl,
	Long categoryId,
	List<Long> categoryPathIds,
	List<String> categoryNames,
	BigDecimal price,
	BigDecimal currentPrice,
	String location,
	ProductStatus productStatus,
	AuctionStatus auctionStatus,
	OffsetDateTime endsAt,
	long viewCount,
	long favoriteCount,
	long searchVersion,
	OffsetDateTime createdAt
) {
}
