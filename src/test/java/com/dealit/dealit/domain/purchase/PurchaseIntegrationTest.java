package com.dealit.dealit.domain.purchase;

import com.dealit.dealit.domain.member.entity.Member;
import com.dealit.dealit.domain.member.repository.MemberRepository;
import com.dealit.dealit.domain.product.ProductSaleType;
import com.dealit.dealit.domain.product.ProductStatus;
import com.dealit.dealit.domain.product.entity.Product;
import com.dealit.dealit.domain.product.repository.ProductRepository;
import com.dealit.dealit.domain.notification.repository.InAppNotificationRepository;
import com.dealit.dealit.domain.payment.repository.ProductPaymentRepository;
import com.dealit.dealit.domain.payment.entity.ProductPaymentStatus;
import com.dealit.dealit.domain.purchase.entity.Purchase;
import com.dealit.dealit.domain.purchase.entity.PurchaseStatus;
import com.dealit.dealit.domain.purchase.repository.PurchaseRepository;
import com.dealit.dealit.domain.purchase.service.PurchaseService;
import com.dealit.dealit.domain.review.repository.ReviewRepository;
import com.dealit.dealit.domain.wallet.entity.WalletLedgerType;
import com.dealit.dealit.domain.wallet.repository.WalletLedgerRepository;
import com.dealit.dealit.domain.wallet.repository.WalletRepository;
import com.dealit.dealit.domain.wallet.service.WalletService;
import com.dealit.dealit.global.security.AuthenticatedMember;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PurchaseIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private PurchaseRepository purchaseRepository;

	@Autowired
	private ReviewRepository reviewRepository;

	@Autowired
	private ProductPaymentRepository productPaymentRepository;

	@Autowired
	private InAppNotificationRepository notificationRepository;

	@Autowired
	private WalletRepository walletRepository;

	@Autowired
	private WalletLedgerRepository walletLedgerRepository;

	@Autowired
	private WalletService walletService;

	@Autowired
	private PurchaseService purchaseService;

	private Member seller;
	private Member buyer;
	private Member otherBuyer;

	@BeforeEach
	void setUp() {
		reviewRepository.deleteAll();
		notificationRepository.deleteAll();
		productPaymentRepository.deleteAll();
		purchaseRepository.deleteAll();
		walletLedgerRepository.deleteAll();
		walletRepository.deleteAll();
		productRepository.deleteAll();
		memberRepository.deleteAll();

		seller = saveMember("purchase-seller", "seller@example.com", "Purchase Seller", true);
		buyer = saveMember("purchase-buyer", "buyer@example.com", "Purchase Buyer", true);
		otherBuyer = saveMember("purchase-other-buyer", "other-buyer@example.com", "Purchase Other Buyer", true);
	}

	@Test
	@DisplayName("딜릿머니로 일반 상품을 구매하면 구매 기록, 지갑 차감, 원장, SOLD 처리가 수행된다")
	void purchaseRegularProductSuccess() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);

		mockMvc.perform(post("/api/v1/products/{productId}/purchase", product.getProductId())
				.with(authentication(authenticatedMember(buyer)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson(UUID.randomUUID().toString())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.purchaseId").isNumber())
			.andExpect(jsonPath("$.productId").value(product.getProductId()))
			.andExpect(jsonPath("$.amount").value(30000))
			.andExpect(jsonPath("$.status").value("PAID"))
			.andExpect(jsonPath("$.purchasedAt").exists());

		assertThat(walletRepository.findByMemberId(buyer.getMemberId()).orElseThrow().getBalance()).isEqualTo(20000);
		assertThat(walletLedgerRepository.findAllByMemberId(
			buyer.getMemberId(),
			org.springframework.data.domain.Pageable.unpaged()
		).getContent())
			.anySatisfy(ledger -> {
				assertThat(ledger.getType()).isEqualTo(WalletLedgerType.PURCHASE);
				assertThat(ledger.getAmount()).isEqualTo(-30000);
				assertThat(ledger.getBalanceAfter()).isEqualTo(20000);
			});
		assertThat(productRepository.findById(product.getProductId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.SOLD);
		assertThat(notificationRepository.countByMemberMemberIdAndReadAtIsNullAndDeletedAtIsNull(seller.getMemberId()))
			.isEqualTo(1);
		assertThat(notificationRepository.countByMemberMemberIdAndReadAtIsNullAndDeletedAtIsNull(buyer.getMemberId()))
			.isEqualTo(3);
		assertThat(purchaseRepository.findAll())
			.singleElement()
			.satisfies(purchase -> {
				assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.PAID);
				assertThat(purchase.getPriceSnapshot()).isEqualByComparingTo(BigDecimal.valueOf(30000));
			});
	}

	@Test
	@DisplayName("잔액이 부족하면 구매는 실패하고 상품과 지갑 상태는 유지된다")
	void purchaseFailsWhenBalanceInsufficient() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 10000);

		mockMvc.perform(post("/api/v1/products/{productId}/purchase", product.getProductId())
				.with(authentication(authenticatedMember(buyer)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson(UUID.randomUUID().toString())))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

		assertThat(productRepository.findById(product.getProductId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(walletRepository.findByMemberId(buyer.getMemberId()).orElseThrow().getBalance()).isEqualTo(10000);
		assertThat(countPurchaseLedgers(buyer.getMemberId())).isZero();
		assertThat(purchaseRepository.findAll()).isEmpty();
	}

	@Test
	@DisplayName("이메일 미인증 사용자는 상품을 구매할 수 없다")
	void unverifiedBuyerPurchaseFails() throws Exception {
		Member unverifiedBuyer = saveMember(
			"purchase-unverified-buyer",
			"unverified-buyer@example.com",
			"Purchase Unverified Buyer",
			false
		);
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(unverifiedBuyer.getMemberId(), 50000);

		mockMvc.perform(post("/api/v1/products/{productId}/purchase", product.getProductId())
				.with(authentication(authenticatedMember(unverifiedBuyer)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson(UUID.randomUUID().toString())))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

		assertThat(productRepository.findById(product.getProductId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(walletRepository.findByMemberId(unverifiedBuyer.getMemberId()).orElseThrow().getBalance()).isEqualTo(50000);
		assertThat(countPurchaseLedgers(unverifiedBuyer.getMemberId())).isZero();
		assertThat(purchaseRepository.findAll()).isEmpty();
	}

	@Test
	@DisplayName("UUID 형식이 아닌 idempotencyKey는 구매 요청을 실패시킨다")
	void invalidIdempotencyKeyPurchaseFails() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);

		mockMvc.perform(post("/api/v1/products/{productId}/purchase", product.getProductId())
				.with(authentication(authenticatedMember(buyer)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson("invalid-key")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		assertThat(productRepository.findById(product.getProductId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(walletRepository.findByMemberId(buyer.getMemberId()).orElseThrow().getBalance()).isEqualTo(50000);
		assertThat(countPurchaseLedgers(buyer.getMemberId())).isZero();
		assertThat(purchaseRepository.findAll()).isEmpty();
	}

	@Test
	@DisplayName("본인 상품은 구매할 수 없다")
	void purchaseOwnProductFails() throws Exception {
		Product product = saveProduct(buyer, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);

		mockMvc.perform(post("/api/v1/products/{productId}/purchase", product.getProductId())
				.with(authentication(authenticatedMember(buyer)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson(UUID.randomUUID().toString())))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_PURCHASABLE"));
	}

	@Test
	@DisplayName("이미 SOLD인 상품은 구매할 수 없다")
	void soldProductPurchaseFails() throws Exception {
		Product product = saveProduct(seller, ProductStatus.SOLD, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);

		mockMvc.perform(post("/api/v1/products/{productId}/purchase", product.getProductId())
				.with(authentication(authenticatedMember(buyer)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson(UUID.randomUUID().toString())))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_PURCHASABLE"));
	}

	@Test
	@DisplayName("같은 idempotencyKey로 같은 상품을 재요청하면 기존 구매 응답을 반환하고 중복 차감하지 않는다")
	void sameIdempotencyKeyReturnsExistingPurchase() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 70000);
		String idempotencyKey = UUID.randomUUID().toString();

		String firstResponse = mockMvc.perform(post("/api/v1/products/{productId}/purchase", product.getProductId())
				.with(authentication(authenticatedMember(buyer)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson(idempotencyKey)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		mockMvc.perform(post("/api/v1/products/{productId}/purchase", product.getProductId())
				.with(authentication(authenticatedMember(buyer)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson(idempotencyKey)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.purchaseId").value(purchaseRepository.findAll().getFirst().getPurchaseId()));

		assertThat(firstResponse).contains("\"status\":\"PAID\"");
		assertThat(walletRepository.findByMemberId(buyer.getMemberId()).orElseThrow().getBalance()).isEqualTo(40000);
		assertThat(countPurchaseLedgers(buyer.getMemberId())).isEqualTo(1);
		assertThat(purchaseRepository.findAll()).hasSize(1);
	}

	@Test
	@DisplayName("같은 idempotencyKey로 다른 상품을 구매하려고 하면 충돌을 반환한다")
	void sameIdempotencyKeyForDifferentProductFails() throws Exception {
		Product firstProduct = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		Product secondProduct = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(20000));
		walletService.charge(buyer.getMemberId(), 70000);
		String idempotencyKey = UUID.randomUUID().toString();

		mockMvc.perform(post("/api/v1/products/{productId}/purchase", firstProduct.getProductId())
				.with(authentication(authenticatedMember(buyer)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson(idempotencyKey)))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/products/{productId}/purchase", secondProduct.getProductId())
				.with(authentication(authenticatedMember(buyer)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson(idempotencyKey)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

		assertThat(purchaseRepository.findAll()).hasSize(1);
		assertThat(countPurchaseLedgers(buyer.getMemberId())).isEqualTo(1);
	}

	@Test
	@DisplayName("동시에 같은 상품을 구매하면 한 요청만 성공한다")
	void concurrentPurchaseAllowsOnlyOneSuccess() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		walletService.charge(otherBuyer.getMemberId(), 50000);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		Future<Integer> first = executor.submit(() -> performConcurrentPurchase(product.getProductId(), buyer, ready, start));
		Future<Integer> second = executor.submit(() -> performConcurrentPurchase(product.getProductId(), otherBuyer, ready, start));

		assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
		start.countDown();

		List<Integer> statuses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
		executor.shutdown();

		assertThat(statuses).contains(200);
		assertThat(statuses).contains(409);
		assertThat(productRepository.findById(product.getProductId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.SOLD);
		assertThat(purchaseRepository.findAll()).hasSize(1);
		assertThat(walletLedgerRepository.findAll().stream()
			.filter(ledger -> ledger.getType() == WalletLedgerType.PURCHASE)
			.count()).isEqualTo(1);

		Purchase purchase = purchaseRepository.findAll().getFirst();
		Long successBuyerId = purchase.getBuyerId();
		Long failedBuyerId = successBuyerId.equals(buyer.getMemberId()) ? otherBuyer.getMemberId() : buyer.getMemberId();
		assertThat(walletRepository.findByMemberId(successBuyerId).orElseThrow().getBalance()).isEqualTo(20000);
		assertThat(walletRepository.findByMemberId(failedBuyerId).orElseThrow().getBalance()).isEqualTo(50000);
	}

	@Test
	@DisplayName("구매자는 영수증을 조회할 수 있다")
	void buyerCanGetReceipt() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);

		mockMvc.perform(get("/api/v1/purchases/{purchaseId}", purchaseId)
				.with(authentication(authenticatedMember(buyer))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.purchaseId").value(purchaseId))
			.andExpect(jsonPath("$.productId").value(product.getProductId()))
			.andExpect(jsonPath("$.productTitle").value("Purchase Product"))
			.andExpect(jsonPath("$.buyerId").value(buyer.getMemberId()))
			.andExpect(jsonPath("$.sellerId").value(seller.getMemberId()))
			.andExpect(jsonPath("$.amount").value(30000))
			.andExpect(jsonPath("$.status").value("PAID"))
			.andExpect(jsonPath("$.purchasedAt").exists())
			.andExpect(jsonPath("$.chatRoomId").isNumber())
			.andExpect(jsonPath("$.productType").value("REGULAR"))
			.andExpect(jsonPath("$.paidAt").exists())
			.andExpect(jsonPath("$.shippedAt").doesNotExist())
			.andExpect(jsonPath("$.completedAt").doesNotExist())
			.andExpect(jsonPath("$.canceledAt").doesNotExist())
			.andExpect(jsonPath("$.sellerShipped").value(false))
			.andExpect(jsonPath("$.buyerConfirmed").value(false))
			.andExpect(jsonPath("$.counterpartNickname").value(seller.getNickname()));
	}

	@Test
	@DisplayName("판매자는 영수증을 조회할 수 있다")
	void sellerCanGetReceipt() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);

		mockMvc.perform(get("/api/v1/purchases/{purchaseId}", purchaseId)
				.with(authentication(authenticatedMember(seller))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.purchaseId").value(purchaseId))
			.andExpect(jsonPath("$.counterpartNickname").value(buyer.getNickname()));
	}

	@Test
	@DisplayName("留덉씠?섏씠吏 alias濡??곸닔利앹쓣 議고쉶?????덈떎")
	void buyerCanGetReceiptThroughMyPageAlias() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);

		mockMvc.perform(get("/api/v1/mypage/purchases/{purchaseId}/receipt", purchaseId)
				.with(authentication(authenticatedMember(buyer))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.purchaseId").value(purchaseId))
			.andExpect(jsonPath("$.productType").value("REGULAR"))
			.andExpect(jsonPath("$.counterpartNickname").value(seller.getNickname()));
	}

	@Test
	@DisplayName("구매자와 판매자가 아닌 사용자는 영수증을 조회할 수 없다")
	void strangerCannotGetReceipt() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);

		mockMvc.perform(get("/api/v1/purchases/{purchaseId}", purchaseId)
				.with(authentication(authenticatedMember(otherBuyer))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PURCHASE_FORBIDDEN"));
	}

	@Test
	@DisplayName("존재하지 않는 영수증은 404를 반환한다")
	void receiptNotFound() throws Exception {
		mockMvc.perform(get("/api/v1/purchases/{purchaseId}", 999999L)
				.with(authentication(authenticatedMember(buyer))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PURCHASE_NOT_FOUND"));
	}

	@Test
	@DisplayName("구매자가 완료 버튼을 누르면 구매자 완료 상태가 저장된다")
	void buyerCompletePurchase() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);

		mockMvc.perform(post("/api/v1/purchases/{purchaseId}/buyer-complete", purchaseId)
				.with(authentication(authenticatedMember(buyer))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PURCHASE_NOT_COMPLETABLE"));

		Purchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
		assertThat(purchase.getBuyerCompletedAt()).isNull();
		assertThat(purchase.getSellerCompletedAt()).isNull();
		assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.PAID);
	}

	@Test
	@DisplayName("판매자가 완료 버튼을 누르면 판매자 완료 상태가 저장된다")
	void sellerCompletePurchase() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);

		mockMvc.perform(post("/api/v1/purchases/{purchaseId}/seller-complete", purchaseId)
				.with(authentication(authenticatedMember(seller))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.purchaseId").value(purchaseId))
			.andExpect(jsonPath("$.status").value("SHIPPED"))
			.andExpect(jsonPath("$.buyerCompleted").value(false))
			.andExpect(jsonPath("$.sellerCompleted").value(true))
			.andExpect(jsonPath("$.buyerCompletedAt").doesNotExist())
			.andExpect(jsonPath("$.sellerCompletedAt").exists())
			.andExpect(jsonPath("$.shippedAt").exists())
			.andExpect(jsonPath("$.autoCompleteAt").exists())
			.andExpect(jsonPath("$.completedAt").doesNotExist())
			.andExpect(jsonPath("$.settledAt").doesNotExist());

		assertThat(notificationRepository.countByMemberMemberIdAndReadAtIsNullAndDeletedAtIsNull(buyer.getMemberId()))
			.isEqualTo(4);

		mockMvc.perform(get("/api/v1/purchases/{purchaseId}", purchaseId)
				.with(authentication(authenticatedMember(buyer))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SHIPPED"))
			.andExpect(jsonPath("$.shippedAt").exists())
			.andExpect(jsonPath("$.completedAt").doesNotExist())
			.andExpect(jsonPath("$.sellerShipped").value(true))
			.andExpect(jsonPath("$.buyerConfirmed").value(false));
	}

	@Test
	@DisplayName("구매자와 판매자가 모두 완료하면 구매 상태가 COMPLETED가 된다")
	void bothCompletePurchase() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);

		mockMvc.perform(post("/api/v1/purchases/{purchaseId}/seller-complete", purchaseId)
				.with(authentication(authenticatedMember(seller))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/purchases/{purchaseId}/buyer-complete", purchaseId)
				.with(authentication(authenticatedMember(buyer))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.purchaseId").value(purchaseId))
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.buyerCompleted").value(true))
			.andExpect(jsonPath("$.sellerCompleted").value(true))
			.andExpect(jsonPath("$.buyerCompletedAt").exists())
			.andExpect(jsonPath("$.sellerCompletedAt").exists())
			.andExpect(jsonPath("$.completedAt").exists())
			.andExpect(jsonPath("$.settledAt").exists());

		Purchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
		assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.COMPLETED);
		assertThat(purchase.getCompletedAt()).isNotNull();
		assertThat(purchase.getSettledAt()).isNotNull();
		assertThat(walletRepository.findByMemberId(seller.getMemberId()).orElseThrow().getBalance()).isEqualTo(30000);
		assertThat(countSettlementLedgers(seller.getMemberId())).isEqualTo(1);
		assertThat(walletRepository.findByMemberId(buyer.getMemberId()).orElseThrow().getBalance()).isEqualTo(20000);
		assertThat(notificationRepository.countByMemberMemberIdAndReadAtIsNullAndDeletedAtIsNull(seller.getMemberId()))
			.isEqualTo(3);
		assertThat(notificationRepository.countByMemberMemberIdAndReadAtIsNullAndDeletedAtIsNull(buyer.getMemberId()))
			.isEqualTo(4);

		mockMvc.perform(get("/api/v1/purchases/{purchaseId}", purchaseId)
				.with(authentication(authenticatedMember(seller))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.shippedAt").exists())
			.andExpect(jsonPath("$.completedAt").exists())
			.andExpect(jsonPath("$.sellerShipped").value(true))
			.andExpect(jsonPath("$.buyerConfirmed").value(true));
	}

	@Test
	@DisplayName("구매자와 판매자가 아닌 사용자는 완료 처리할 수 없다")
	void strangerCannotCompletePurchase() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);

		mockMvc.perform(post("/api/v1/purchases/{purchaseId}/buyer-complete", purchaseId)
				.with(authentication(authenticatedMember(otherBuyer))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PURCHASE_FORBIDDEN"));

		mockMvc.perform(post("/api/v1/purchases/{purchaseId}/seller-complete", purchaseId)
				.with(authentication(authenticatedMember(otherBuyer))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PURCHASE_FORBIDDEN"));
	}

	@Test
	@DisplayName("완료 API를 중복 호출해도 판매자 정산은 한 번만 수행된다")
	void duplicateCompleteDoesNotDuplicateSettlement() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);

		mockMvc.perform(post("/api/v1/purchases/{purchaseId}/seller-complete", purchaseId)
				.with(authentication(authenticatedMember(seller))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/purchases/{purchaseId}/buyer-complete", purchaseId)
				.with(authentication(authenticatedMember(buyer))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.settledAt").exists());
		mockMvc.perform(post("/api/v1/purchases/{purchaseId}/seller-complete", purchaseId)
				.with(authentication(authenticatedMember(seller))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.settledAt").exists());

		assertThat(walletRepository.findByMemberId(seller.getMemberId()).orElseThrow().getBalance()).isEqualTo(30000);
		assertThat(countSettlementLedgers(seller.getMemberId())).isEqualTo(1);
		assertThat(purchaseRepository.findById(purchaseId).orElseThrow().isSettled()).isTrue();
	}

	@Test
	@DisplayName("판매자가 3일 내 발송하지 않으면 자동 취소되고 구매자에게 환불된다")
	void expiredUnshippedPurchaseIsCanceledAndRefunded() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);

		int processed = purchaseService.cancelExpiredUnshippedPurchases(LocalDateTime.now().plusDays(4));

		assertThat(processed).isEqualTo(1);
		Purchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
		assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.CANCELED);
		assertThat(purchase.getCanceledAt()).isNotNull();
		assertThat(productPaymentRepository.findByPurchaseId(purchaseId).orElseThrow().getStatus())
			.isEqualTo(ProductPaymentStatus.REFUNDED);
		assertThat(walletRepository.findByMemberId(buyer.getMemberId()).orElseThrow().getBalance()).isEqualTo(50000);
		assertThat(countRefundLedgers(buyer.getMemberId())).isEqualTo(1);
	}

	@Test
	@DisplayName("판매자 발송 후 7일이 지나면 자동 수령확정과 판매자 정산이 처리된다")
	void shippedPurchaseIsAutoCompletedAfterSevenDays() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);

		mockMvc.perform(post("/api/v1/purchases/{purchaseId}/ship", purchaseId)
				.with(authentication(authenticatedMember(seller))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SHIPPED"));

		int processed = purchaseService.autoCompleteShippedPurchases(LocalDateTime.now().plusDays(8));

		assertThat(processed).isEqualTo(1);
		Purchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
		assertThat(purchase.getStatus()).isEqualTo(PurchaseStatus.COMPLETED);
		assertThat(purchase.getBuyerCompletedAt()).isNotNull();
		assertThat(purchase.getSettledAt()).isNotNull();
		assertThat(productPaymentRepository.findByPurchaseId(purchaseId).orElseThrow().getStatus())
			.isEqualTo(ProductPaymentStatus.SETTLED);
		assertThat(walletRepository.findByMemberId(seller.getMemberId()).orElseThrow().getBalance()).isEqualTo(30000);
		assertThat(productRepository.findById(product.getProductId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.ENDED);
	}

	@Test
	@DisplayName("마이페이지에서 구매자는 본인 구매내역을 조회할 수 있다")
	void buyerCanGetMyPurchases() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);

		mockMvc.perform(get("/api/v1/mypage/purchases")
				.with(authentication(authenticatedMember(buyer))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].purchaseId").value(purchaseId))
			.andExpect(jsonPath("$.content[0].productId").value(product.getProductId()))
			.andExpect(jsonPath("$.content[0].productTitle").value("Purchase Product"))
			.andExpect(jsonPath("$.content[0].counterpartMemberId").value(seller.getMemberId()))
			.andExpect(jsonPath("$.content[0].amount").value(30000))
			.andExpect(jsonPath("$.content[0].status").value("PAID"))
			.andExpect(jsonPath("$.content[0].purchasedAt").exists())
			.andExpect(jsonPath("$.content[0].chatRoomId").isNumber())
			.andExpect(jsonPath("$.content[0].productType").value("REGULAR"))
			.andExpect(jsonPath("$.content[0].auctionId").doesNotExist())
			.andExpect(jsonPath("$.content[0].sellerShipped").value(false))
			.andExpect(jsonPath("$.content[0].buyerConfirmed").value(false))
			.andExpect(jsonPath("$.content[0].completed").value(false))
			.andExpect(jsonPath("$.content[0].shippedAt").doesNotExist())
			.andExpect(jsonPath("$.content[0].completedAt").doesNotExist())
			.andExpect(jsonPath("$.content[0].reviewWritten").value(false))
			.andExpect(jsonPath("$.content[0].reviewAvailable").value(false))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	@DisplayName("마이페이지 구매내역은 status 필터가 적용된다")
	void buyerCanFilterMyPurchasesByStatus() throws Exception {
		Product paidProduct = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		Product shippedProduct = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(20000));
		walletService.charge(buyer.getMemberId(), 70000);
		purchaseProduct(paidProduct, buyer);
		Long shippedPurchaseId = purchaseProduct(shippedProduct, buyer);

		mockMvc.perform(post("/api/v1/purchases/{purchaseId}/ship", shippedPurchaseId)
				.with(authentication(authenticatedMember(seller))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SHIPPED"));

		mockMvc.perform(get("/api/v1/mypage/purchases")
				.param("status", "SHIPPED")
				.with(authentication(authenticatedMember(buyer))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].purchaseId").value(shippedPurchaseId))
			.andExpect(jsonPath("$.content[0].status").value("SHIPPED"))
			.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	@DisplayName("마이페이지 구매내역은 본인 데이터만 조회된다")
	void buyerCanOnlyGetOwnPurchases() throws Exception {
		Product buyerProduct = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		Product otherBuyerProduct = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(20000));
		walletService.charge(buyer.getMemberId(), 50000);
		walletService.charge(otherBuyer.getMemberId(), 50000);
		Long buyerPurchaseId = purchaseProduct(buyerProduct, buyer);
		purchaseProduct(otherBuyerProduct, otherBuyer);

		mockMvc.perform(get("/api/v1/mypage/purchases")
				.with(authentication(authenticatedMember(buyer))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].purchaseId").value(buyerPurchaseId))
			.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	@DisplayName("마이페이지에서 판매자는 본인 판매내역을 조회할 수 있다")
	void sellerCanGetMySales() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);

		mockMvc.perform(get("/api/v1/mypage/sales")
				.with(authentication(authenticatedMember(seller))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].purchaseId").value(purchaseId))
			.andExpect(jsonPath("$.content[0].productId").value(product.getProductId()))
			.andExpect(jsonPath("$.content[0].productTitle").value("Purchase Product"))
			.andExpect(jsonPath("$.content[0].counterpartMemberId").value(buyer.getMemberId()))
			.andExpect(jsonPath("$.content[0].amount").value(30000))
			.andExpect(jsonPath("$.content[0].status").value("PAID"))
			.andExpect(jsonPath("$.content[0].purchasedAt").exists())
			.andExpect(jsonPath("$.content[0].chatRoomId").isNumber())
			.andExpect(jsonPath("$.content[0].productType").value("REGULAR"))
			.andExpect(jsonPath("$.content[0].auctionId").doesNotExist())
			.andExpect(jsonPath("$.content[0].sellerShipped").value(false))
			.andExpect(jsonPath("$.content[0].buyerConfirmed").value(false))
			.andExpect(jsonPath("$.content[0].completed").value(false))
			.andExpect(jsonPath("$.content[0].shippedAt").doesNotExist())
			.andExpect(jsonPath("$.content[0].completedAt").doesNotExist())
			.andExpect(jsonPath("$.content[0].reviewReceived").value(false))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	@DisplayName("거래 완료 후 구매내역에는 리뷰 작성 가능 상태가 표시된다")
	void completedPurchaseShowsReviewAvailable() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);
		completePurchase(purchaseId);

		mockMvc.perform(get("/api/v1/mypage/purchases")
				.with(authentication(authenticatedMember(buyer))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].purchaseId").value(purchaseId))
			.andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
			.andExpect(jsonPath("$.content[0].sellerShipped").value(true))
			.andExpect(jsonPath("$.content[0].buyerConfirmed").value(true))
			.andExpect(jsonPath("$.content[0].completed").value(true))
			.andExpect(jsonPath("$.content[0].shippedAt").exists())
			.andExpect(jsonPath("$.content[0].completedAt").exists())
			.andExpect(jsonPath("$.content[0].reviewWritten").value(false))
			.andExpect(jsonPath("$.content[0].reviewAvailable").value(true));
	}

	@Test
	@DisplayName("리뷰 작성 후 구매/판매내역에는 리뷰 상태가 반영된다")
	void reviewStateIsReflectedInMyPageLists() throws Exception {
		Product product = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		walletService.charge(buyer.getMemberId(), 50000);
		Long purchaseId = purchaseProduct(product, buyer);
		completePurchase(purchaseId);

		mockMvc.perform(post("/api/v1/reviews")
				.with(authentication(authenticatedMember(buyer)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(reviewRequestJson(product.getProductId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.productId").value(product.getProductId()))
			.andExpect(jsonPath("$.reviewerId").value(buyer.getMemberId()))
			.andExpect(jsonPath("$.revieweeId").value(seller.getMemberId()));

		assertThat(notificationRepository.findAll())
			.anySatisfy(notification -> {
				assertThat(notification.getTitle()).isEqualTo("리뷰가 등록되었습니다.");
				assertThat(notification.getTargetType()).isEqualTo("REVIEW");
				assertThat(notification.getTargetUrl()).isEqualTo("/mypage/review");
			});

		mockMvc.perform(get("/api/v1/mypage/purchases")
				.with(authentication(authenticatedMember(buyer))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].purchaseId").value(purchaseId))
			.andExpect(jsonPath("$.content[0].reviewWritten").value(true))
			.andExpect(jsonPath("$.content[0].reviewAvailable").value(false));

		mockMvc.perform(get("/api/v1/mypage/sales")
				.with(authentication(authenticatedMember(seller))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].purchaseId").value(purchaseId))
			.andExpect(jsonPath("$.content[0].reviewReceived").value(true));
	}

	@Test
	@DisplayName("마이페이지 판매내역은 status 필터가 적용된다")
	void sellerCanFilterMySalesByStatus() throws Exception {
		Product paidProduct = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		Product shippedProduct = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(20000));
		walletService.charge(buyer.getMemberId(), 70000);
		purchaseProduct(paidProduct, buyer);
		Long shippedPurchaseId = purchaseProduct(shippedProduct, buyer);

		mockMvc.perform(post("/api/v1/purchases/{purchaseId}/ship", shippedPurchaseId)
				.with(authentication(authenticatedMember(seller))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SHIPPED"));

		mockMvc.perform(get("/api/v1/mypage/sales")
				.param("status", "SHIPPED")
				.with(authentication(authenticatedMember(seller))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].purchaseId").value(shippedPurchaseId))
			.andExpect(jsonPath("$.content[0].status").value("SHIPPED"))
			.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	@DisplayName("마이페이지 판매내역은 본인 데이터만 조회된다")
	void sellerCanOnlyGetOwnSales() throws Exception {
		Member otherSeller = saveMember("purchase-other-seller", "other-seller@example.com", "Purchase Other Seller", true);
		Product sellerProduct = saveProduct(seller, ProductStatus.ON_SALE, BigDecimal.valueOf(30000));
		Product otherSellerProduct = saveProduct(otherSeller, ProductStatus.ON_SALE, BigDecimal.valueOf(20000));
		walletService.charge(buyer.getMemberId(), 70000);
		Long sellerPurchaseId = purchaseProduct(sellerProduct, buyer);
		purchaseProduct(otherSellerProduct, buyer);

		mockMvc.perform(get("/api/v1/mypage/sales")
				.with(authentication(authenticatedMember(seller))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].purchaseId").value(sellerPurchaseId))
			.andExpect(jsonPath("$.totalElements").value(1));
	}

	private Member saveMember(String loginId, String email, String name, boolean verified) {
		Member member = memberRepository.save(Member.create(loginId, "password", email, name, verified));
		member.assignDefaultNickname();
		return memberRepository.save(member);
	}

	private Product saveProduct(Member seller, ProductStatus status, BigDecimal price) {
		return productRepository.save(Product.create(
			"Purchase Product",
			"Purchase product description",
			ProductSaleType.REGULAR,
			19L,
			seller.getMemberId(),
			price,
			false,
			"Seoul Gangnam",
			null,
			status
		));
	}

	private long countPurchaseLedgers(Long memberId) {
		return walletLedgerRepository.findAllByMemberId(
				memberId,
				org.springframework.data.domain.Pageable.unpaged()
			).getContent().stream()
			.filter(ledger -> ledger.getType() == WalletLedgerType.PURCHASE)
			.count();
	}

	private long countSettlementLedgers(Long memberId) {
		return walletLedgerRepository.findAllByMemberId(
				memberId,
				org.springframework.data.domain.Pageable.unpaged()
			).getContent().stream()
			.filter(ledger -> ledger.getType() == WalletLedgerType.SETTLEMENT)
			.count();
	}

	private long countRefundLedgers(Long memberId) {
		return walletLedgerRepository.findAllByMemberId(
				memberId,
				org.springframework.data.domain.Pageable.unpaged()
			).getContent().stream()
			.filter(ledger -> ledger.getType() == WalletLedgerType.REFUND)
			.count();
	}

	private Long purchaseProduct(Product product, Member buyer) throws Exception {
		mockMvc.perform(post("/api/v1/products/{productId}/purchase", product.getProductId())
				.with(authentication(authenticatedMember(buyer)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson(UUID.randomUUID().toString())))
			.andExpect(status().isOk());

		return purchaseRepository.findAll().getFirst().getPurchaseId();
	}

	private void completePurchase(Long purchaseId) throws Exception {
		mockMvc.perform(post("/api/v1/purchases/{purchaseId}/ship", purchaseId)
				.with(authentication(authenticatedMember(seller))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/purchases/{purchaseId}/buyer-complete", purchaseId)
				.with(authentication(authenticatedMember(buyer))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	private int performConcurrentPurchase(
		Long productId,
		Member buyer,
		CountDownLatch ready,
		CountDownLatch start
	) throws Exception {
		ready.countDown();
		start.await(5, TimeUnit.SECONDS);

		return mockMvc.perform(post("/api/v1/products/{productId}/purchase", productId)
				.with(authentication(authenticatedMember(buyer)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson(UUID.randomUUID().toString())))
			.andReturn()
			.getResponse()
			.getStatus();
	}

	private String requestJson(String idempotencyKey) {
		return """
			{
			  "idempotencyKey": "%s"
			}
			""".formatted(idempotencyKey);
	}

	private String reviewRequestJson(Long productId) {
		return """
			{
			  "productId": %d,
			  "rating": 5.0,
			  "content": "Great transaction."
			}
			""".formatted(productId);
	}

	private UsernamePasswordAuthenticationToken authenticatedMember(Member member) {
		AuthenticatedMember principal = new AuthenticatedMember(member.getMemberId(), member.getLoginId(), "ROLE_USER");
		return new UsernamePasswordAuthenticationToken(
			principal,
			null,
			List.of(new SimpleGrantedAuthority("ROLE_USER"))
		);
	}
}
