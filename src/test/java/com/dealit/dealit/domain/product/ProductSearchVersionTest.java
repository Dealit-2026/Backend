package com.dealit.dealit.domain.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealit.dealit.domain.product.entity.Product;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductSearchVersionTest {

	@Test
	@DisplayName("상품 생성 시 searchVersion은 1로 시작한다")
	void productSearchVersionStartsFromOne() {
		Product product = createProduct();

		assertThat(product.getSearchVersion()).isEqualTo(1L);
	}

	@Test
	@DisplayName("상품 searchVersion은 단조 증가한다")
	void increaseSearchVersion() {
		Product product = createProduct();

		long increasedVersion = product.increaseSearchVersion();

		assertThat(increasedVersion).isEqualTo(2L);
		assertThat(product.getSearchVersion()).isEqualTo(2L);
	}

	private Product createProduct() {
		return Product.create(
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
	}
}
