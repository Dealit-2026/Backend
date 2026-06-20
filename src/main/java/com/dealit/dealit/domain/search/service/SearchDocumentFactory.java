package com.dealit.dealit.domain.search.service;

import com.dealit.dealit.domain.auction.entity.Auction;
import com.dealit.dealit.domain.auction.entity.Category;
import com.dealit.dealit.domain.auction.service.CategoryQueryService;
import com.dealit.dealit.domain.product.entity.Product;
import com.dealit.dealit.domain.product.service.ProductThumbnailResolver;
import com.dealit.dealit.domain.search.document.SearchDocument;
import com.dealit.dealit.domain.search.dto.SearchResultType;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchDocumentFactory {

	private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

	private final ProductThumbnailResolver productThumbnailResolver;
	private final CategoryQueryService categoryQueryService;

	public SearchDocument regularProduct(Product product) {
		return regularProduct(product, loadCategoryNodes());
	}

	public SearchDocument regularProduct(Product product, Map<Long, CategoryNode> nodesById) {
		return new SearchDocument(
			"REGULAR-" + product.getProductId(),
			SearchResultType.REGULAR,
			product.getProductId(),
			null,
			product.getName(),
			product.getDescription(),
			productThumbnailResolver.resolve(product),
			product.getCategoryId(),
			resolveCategoryPathIds(product.getCategoryId(), nodesById),
			resolveCategoryNames(product.getCategoryId(), nodesById),
			product.getPrice(),
			null,
			product.getLocation(),
			product.getStatus(),
			null,
			null,
			product.getViewCount(),
			product.getFavoriteCount(),
			product.getSearchVersion(),
			toSeoulOffsetDateTime(product.getCreatedAt())
		);
	}

	public SearchDocument auction(Auction auction) {
		return auction(auction, loadCategoryNodes());
	}

	public SearchDocument auction(Auction auction, Map<Long, CategoryNode> nodesById) {
		Product product = auction.getProduct();
		return new SearchDocument(
			"AUCTION-" + auction.getAuctionId(),
			SearchResultType.AUCTION,
			product.getProductId(),
			auction.getAuctionId(),
			product.getName(),
			product.getDescription(),
			productThumbnailResolver.resolve(product),
			product.getCategoryId(),
			resolveCategoryPathIds(product.getCategoryId(), nodesById),
			resolveCategoryNames(product.getCategoryId(), nodesById),
			null,
			auction.getCurrentPrice(),
			product.getLocation(),
			product.getStatus(),
			auction.getStatus(),
			auction.getEndsAt(),
			product.getViewCount(),
			product.getFavoriteCount(),
			product.getSearchVersion(),
			toSeoulOffsetDateTime(product.getCreatedAt())
		);
	}

	public Map<Long, CategoryNode> loadCategoryNodes() {
		List<Category> categories = categoryQueryService.findAllOrdered();
		Map<Long, CategoryNode> nodesById = new LinkedHashMap<>();
		for (Category category : categories) {
			nodesById.put(category.getId(), new CategoryNode(category));
		}
		for (Category category : categories) {
			if (category.getParentId() == null) {
				continue;
			}
			CategoryNode node = nodesById.get(category.getId());
			CategoryNode parent = nodesById.get(category.getParentId());
			if (node != null && parent != null) {
				node.parent(parent);
			}
		}
		return nodesById;
	}

	private List<Long> resolveCategoryPathIds(Long categoryId, Map<Long, CategoryNode> nodesById) {
		List<Long> path = new ArrayList<>();
		CategoryNode node = nodesById.get(categoryId);
		while (node != null) {
			path.add(0, node.category().getId());
			node = node.parent();
		}
		if (path.isEmpty() && categoryId != null) {
			path.add(categoryId);
		}
		return path;
	}

	private List<String> resolveCategoryNames(Long categoryId, Map<Long, CategoryNode> nodesById) {
		List<String> names = new ArrayList<>();
		CategoryNode node = nodesById.get(categoryId);
		while (node != null) {
			names.add(0, node.category().getNameKo());
			node = node.parent();
		}
		return names;
	}

	private OffsetDateTime toSeoulOffsetDateTime(LocalDateTime dateTime) {
		if (dateTime == null) {
			return null;
		}
		return dateTime.atZone(SEOUL_ZONE).toOffsetDateTime();
	}

	public static final class CategoryNode {

		private final Category category;
		private CategoryNode parent;

		private CategoryNode(Category category) {
			this.category = category;
		}

		private Category category() {
			return category;
		}

		private CategoryNode parent() {
			return parent;
		}

		private void parent(CategoryNode parent) {
			this.parent = parent;
		}
	}
}
