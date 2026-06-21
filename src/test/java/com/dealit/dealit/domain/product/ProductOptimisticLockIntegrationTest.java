package com.dealit.dealit.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dealit.dealit.domain.auction.AuctionStatus;
import com.dealit.dealit.domain.auction.entity.Auction;
import com.dealit.dealit.domain.auction.repository.AuctionRepository;
import com.dealit.dealit.domain.product.entity.Product;
import com.dealit.dealit.domain.product.repository.ProductRepository;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class ProductOptimisticLockIntegrationTest {

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private AuctionRepository auctionRepository;

	private TransactionTemplate transactionTemplate;

	@Autowired
	void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void setUp() {
		auctionRepository.deleteAll();
		productRepository.deleteAll();
	}

	@Test
	@DisplayName("Product optimistic locking prevents duplicate searchVersion commits")
	void productOptimisticLockPreventsDuplicateSearchVersionCommits() {
		Long productId = transactionTemplate.execute(status -> productRepository.save(createProduct()).getProductId());

		Product staleProduct = transactionTemplate.execute(status ->
			productRepository.findById(productId).orElseThrow()
		);
		transactionTemplate.executeWithoutResult(status -> {
			Product currentProduct = productRepository.findById(productId).orElseThrow();
			currentProduct.increaseSearchVersion();
		});

		staleProduct.increaseSearchVersion();
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
			productRepository.saveAndFlush(staleProduct)
		)).isInstanceOfAny(OptimisticLockingFailureException.class, OptimisticLockException.class);

		Long searchVersion = transactionTemplate.execute(status ->
			productRepository.findById(productId).orElseThrow().getSearchVersion()
		);
		assertThat(searchVersion).isEqualTo(2L);
	}

	@Test
	@DisplayName("Auction optimistic locking prevents duplicate searchVersion commits")
	void auctionOptimisticLockPreventsDuplicateSearchVersionCommits() {
		Long auctionId = transactionTemplate.execute(status -> {
			Product product = productRepository.save(createProduct());
			return auctionRepository.save(createAuction(product)).getAuctionId();
		});

		Auction staleAuction = transactionTemplate.execute(status ->
			auctionRepository.findById(auctionId).orElseThrow()
		);
		transactionTemplate.executeWithoutResult(status -> {
			Auction currentAuction = auctionRepository.findById(auctionId).orElseThrow();
			currentAuction.increaseSearchVersion();
		});

		staleAuction.increaseSearchVersion();
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
			auctionRepository.saveAndFlush(staleAuction)
		)).isInstanceOfAny(OptimisticLockingFailureException.class, OptimisticLockException.class);

		Long searchVersion = transactionTemplate.execute(status ->
			auctionRepository.findById(auctionId).orElseThrow().getSearchVersion()
		);
		assertThat(searchVersion).isEqualTo(2L);
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

	private Auction createAuction(Product product) {
		return Auction.create(
			product,
			BigDecimal.valueOf(10000),
			BigDecimal.valueOf(1000),
			OffsetDateTime.now().minusDays(1),
			OffsetDateTime.now().plusDays(1),
			AuctionStatus.ONGOING
		);
	}
}
