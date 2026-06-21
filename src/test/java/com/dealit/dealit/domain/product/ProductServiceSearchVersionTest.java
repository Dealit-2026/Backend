package com.dealit.dealit.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dealit.dealit.domain.auction.entity.Category;
import com.dealit.dealit.domain.auction.repository.CategoryRepository;
import com.dealit.dealit.domain.auction.service.CategoryQueryService;
import com.dealit.dealit.domain.category.service.CategoryRecommendationService;
import com.dealit.dealit.domain.member.entity.Member;
import com.dealit.dealit.domain.member.repository.MemberInterestCategoryRepository;
import com.dealit.dealit.domain.member.repository.MemberRepository;
import com.dealit.dealit.domain.price.client.AiPriceRecommendationClient;
import com.dealit.dealit.domain.product.dto.UpdateProductRequest;
import com.dealit.dealit.domain.product.entity.Product;
import com.dealit.dealit.domain.product.repository.ProductDraftRepository;
import com.dealit.dealit.domain.product.repository.ProductImageRepository;
import com.dealit.dealit.domain.product.repository.ProductRepository;
import com.dealit.dealit.domain.product.service.ProductImageStorage;
import com.dealit.dealit.domain.product.service.ProductService;
import com.dealit.dealit.domain.search.event.ProductSearchIndexRequestedEvent;
import com.dealit.dealit.global.service.ImageUrlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductServiceSearchVersionTest {

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ProductImageRepository productImageRepository;

	@Mock
	private ProductDraftRepository productDraftRepository;

	@Mock
	private CategoryRepository categoryRepository;

	@Mock
	private CategoryQueryService categoryQueryService;

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private MemberInterestCategoryRepository memberInterestCategoryRepository;

	@Mock
	private ProductImageStorage productImageStorage;

	@Mock
	private ImageUrlService imageUrlService;

	@Mock
	private CategoryRecommendationService categoryRecommendationService;

	@Mock
	private AiPriceRecommendationClient aiPriceRecommendationClient;

	@Mock
	private ObjectMapper objectMapper;

	@Mock
	private ApplicationEventPublisher applicationEventPublisher;

	@InjectMocks
	private ProductService productService;

	@Test
	@DisplayName("상품 수정 시 searchVersion이 증가하고 이벤트에 증가된 version이 담긴다")
	void updateProductIncreasesSearchVersionAndPublishesEventVersion() {
		Long memberId = 1L;
		Long productId = 10L;
		Product product = createProduct(memberId, productId);
		Member member = createMember(memberId);
		Category category = createLeafCategory(19L);
		UpdateProductRequest request = new UpdateProductRequest(
			"updated",
			"updated description",
			19L,
			BigDecimal.valueOf(20000),
			true,
			"Busan",
			List.of()
		);

		when(memberRepository.findByMemberIdAndDeletedAtIsNull(memberId)).thenReturn(Optional.of(member));
		when(productRepository.findByProductIdAndMemberIdAndDeletedAtIsNull(productId, memberId)).thenReturn(Optional.of(product));
		when(categoryRepository.findById(19L)).thenReturn(Optional.of(category));
		when(productImageRepository.findAllByImageIdInAndDeletedAtIsNull(anyCollection())).thenReturn(List.of());

		productService.updateProduct(memberId, productId, request);

		ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
		verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
		ProductSearchIndexRequestedEvent event = (ProductSearchIndexRequestedEvent) eventCaptor.getValue();

		assertThat(product.getSearchVersion()).isEqualTo(2L);
		assertThat(event.productId()).isEqualTo(productId);
		assertThat(event.searchVersion()).isEqualTo(2L);
	}

	private Product createProduct(Long memberId, Long productId) {
		Product product = Product.create(
			"product",
			"description",
			ProductSaleType.REGULAR,
			19L,
			memberId,
			BigDecimal.valueOf(10000),
			false,
			"Seoul",
			null,
			ProductStatus.ON_SALE
		);
		ReflectionTestUtils.setField(product, "productId", productId);
		return product;
	}

	private Member createMember(Long memberId) {
		Member member = Member.create("member", "password", "member@example.com", null, "member");
		ReflectionTestUtils.setField(member, "memberId", memberId);
		return member;
	}

	private Category createLeafCategory(Long categoryId) {
		Category category = mock(Category.class);
		when(category.getId()).thenReturn(categoryId);
		when(category.getNameKo()).thenReturn("category");
		when(category.getDepth()).thenReturn(3);
		return category;
	}
}
