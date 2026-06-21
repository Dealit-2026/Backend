package com.dealit.dealit.domain.product.entity;

import com.dealit.dealit.domain.product.ProductSaleType;
import com.dealit.dealit.domain.product.ProductStatus;
import com.dealit.dealit.global.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Getter
@Entity
@Table(name = "product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SequenceGenerator(
	name = "product_seq_generator",
	sequenceName = "product_seq",
	allocationSize = 1
)
public class Product extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "product_seq_generator")
	@Column(name = "product_id")
	private Long productId;

	@Version
	@Column(name = "lock_version", nullable = false)
	private long lockVersion;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Column(name = "description", nullable = false, length = 2000)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(name = "sale_type", nullable = false, length = 20)
	private ProductSaleType saleType;

	@Column(name = "category_id", nullable = false)
	private Long categoryId;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "price", nullable = false, precision = 15, scale = 2)
	private BigDecimal price;

	@Column(name = "allow_offer", nullable = false)
	private boolean allowOffer;

	@Column(name = "location", nullable = false, length = 100)
	private String location;

	@Column(name = "draft_id")
	private Long draftId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private ProductStatus status;

	@Column(name = "view_count", nullable = false)
	private long viewCount = 0L;

	@Column(name = "favorite_count", nullable = false)
	private long favoriteCount = 0L;

	@Column(name = "chat_count", nullable = false)
	private long chatCount = 0L;

	@Column(name = "search_version", nullable = false)
	private long searchVersion = 1L;

	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL)
	private final List<ProductImage> images = new ArrayList<>();

	private Product(
		String name,
		String description,
		ProductSaleType saleType,
		Long categoryId,
		Long memberId,
		BigDecimal price,
		boolean allowOffer,
		String location,
		Long draftId,
		ProductStatus status
	) {
		this.name = name;
		this.description = description;
		this.saleType = saleType;
		this.categoryId = categoryId;
		this.memberId = memberId;
		this.price = price;
		this.allowOffer = allowOffer;
		this.location = location;
		this.draftId = draftId;
		this.status = status;
	}

	public static Product create(
		String name,
		String description,
		ProductSaleType saleType,
		Long categoryId,
		Long memberId,
		BigDecimal price,
		boolean allowOffer,
		String location,
		Long draftId,
		ProductStatus status
	) {
		return new Product(name, description, saleType, categoryId, memberId, price, allowOffer, location, draftId, status);
	}

	public void attachImage(ProductImage image, int sortOrder) {
		if (!images.contains(image)) {
			images.add(image);
		}
		image.assignToProduct(this, sortOrder);
	}

	public void softDeleteWithImages() {
		softDelete();
		for (ProductImage image : images) {
			image.softDelete();
		}
	}

	public void increaseViewCount() {
		this.viewCount++;
	}

	public void increaseFavoriteCount() {
		this.favoriteCount++;
	}

	public void decreaseFavoriteCount() {
		if (this.favoriteCount > 0) {
			this.favoriteCount--;
		}
	}

	public void updateEditableDetails(
		String name,
		String description,
		Long categoryId,
		BigDecimal price,
		String location
	) {
		this.name = name;
		this.description = description;
		this.categoryId = categoryId;
		this.price = price;
		this.location = location;
	}

	public void updateAllowOffer(boolean allowOffer) {
		this.allowOffer = allowOffer;
	}

	public long increaseSearchVersion() {
		return ++this.searchVersion;
	}

	public void markSold() {
		this.status = ProductStatus.SOLD;
	}

	public void markEnded() {
		this.status = ProductStatus.ENDED;
		softDelete();
	}

	public void markEndedWithoutDelete() {
		this.status = ProductStatus.ENDED;
	}

	public void markTradeCompleted() {
		this.status = ProductStatus.ENDED;
	}

	public void replaceImages(Collection<ProductImage> nextImages) {
		List<ProductImage> removedImages = images.stream()
			.filter(image -> !nextImages.contains(image))
			.toList();

		for (ProductImage removedImage : removedImages) {
			removedImage.detachFromProduct();
		}
		images.removeAll(removedImages);

		for (ProductImage nextImage : nextImages) {
			if (!images.contains(nextImage)) {
				images.add(nextImage);
			}
		}
	}

	public void removeImage(ProductImage image) {
		if (images.remove(image)) {
			image.detachFromProduct();
		}
	}
}
