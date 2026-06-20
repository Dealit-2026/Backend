package com.dealit.dealit.domain.product.service;

import com.dealit.dealit.domain.auction.entity.Category;
import com.dealit.dealit.domain.auction.repository.CategoryRepository;
import com.dealit.dealit.domain.auction.service.CategoryQueryService;
import com.dealit.dealit.domain.auth.exception.InvalidCredentialsException;
import com.dealit.dealit.domain.category.dto.CategoryRecommendationResult;
import com.dealit.dealit.domain.category.service.CategoryRecommendationService;
import com.dealit.dealit.domain.member.entity.Member;
import com.dealit.dealit.domain.member.exception.EmailNotVerifiedException;
import com.dealit.dealit.domain.member.repository.MemberInterestCategoryRepository;
import com.dealit.dealit.domain.member.repository.MemberRepository;
import com.dealit.dealit.domain.price.client.AiPriceRecommendationClient;
import com.dealit.dealit.domain.price.dto.AiPriceRecommendationRequest;
import com.dealit.dealit.domain.price.dto.AiPriceRecommendationResponse;
import com.dealit.dealit.domain.product.ProductSaleType;
import com.dealit.dealit.domain.product.ProductStatus;
import com.dealit.dealit.domain.product.dto.CategoryOptionResponse;
import com.dealit.dealit.domain.product.dto.CreateProductRequest;
import com.dealit.dealit.domain.product.dto.CreateProductResponse;
import com.dealit.dealit.domain.product.dto.DeleteProductImageResponse;
import com.dealit.dealit.domain.product.dto.DeleteProductResponse;
import com.dealit.dealit.domain.product.dto.HotListProductItemResponse;
import com.dealit.dealit.domain.product.dto.HotListProductListResponse;
import com.dealit.dealit.domain.product.dto.MySellingProductItemResponse;
import com.dealit.dealit.domain.product.dto.MySellingProductListResponse;
import com.dealit.dealit.domain.product.dto.PopularProductItemResponse;
import com.dealit.dealit.domain.product.dto.PopularProductListResponse;
import com.dealit.dealit.domain.product.dto.ProductSearchItemResponse;
import com.dealit.dealit.domain.product.dto.ProductSearchListResponse;
import com.dealit.dealit.domain.product.dto.ProductEditDetailResponse;
import com.dealit.dealit.domain.product.dto.ProductEditDetailResponse.ProductEditImageResponse;
import com.dealit.dealit.domain.product.dto.ProductImagePayload;
import com.dealit.dealit.domain.product.dto.RecommendCategoryRequest;
import com.dealit.dealit.domain.product.dto.RecommendCategoryResponse;
import com.dealit.dealit.domain.product.dto.RecommendPriceRequest;
import com.dealit.dealit.domain.product.dto.RecommendPriceResponse;
import com.dealit.dealit.domain.product.dto.SaveProductDraftRequest;
import com.dealit.dealit.domain.product.dto.SaveProductDraftResponse;
import com.dealit.dealit.domain.product.dto.SearchCategoryOptionResponse;
import com.dealit.dealit.domain.product.dto.UpdateProductRequest;
import com.dealit.dealit.domain.product.dto.UploadProductImageResponse;
import com.dealit.dealit.domain.product.entity.Product;
import com.dealit.dealit.domain.product.entity.ProductDraft;
import com.dealit.dealit.domain.product.entity.ProductImage;
import com.dealit.dealit.domain.product.exception.InvalidProductRequestException;
import com.dealit.dealit.domain.product.exception.ProductAccessDeniedException;
import com.dealit.dealit.domain.product.exception.ProductImageNotFoundException;
import com.dealit.dealit.domain.product.repository.ProductDraftRepository;
import com.dealit.dealit.domain.product.repository.ProductImageRepository;
import com.dealit.dealit.domain.product.repository.ProductRepository;
import com.dealit.dealit.domain.search.event.ProductSearchDeleteRequestedEvent;
import com.dealit.dealit.domain.search.event.ProductSearchIndexRequestedEvent;
import com.dealit.dealit.global.service.ImageUrlService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

	private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

	private final ProductRepository productRepository;
	private final ProductImageRepository productImageRepository;
	private final ProductDraftRepository productDraftRepository;
	private final CategoryRepository categoryRepository;
	private final CategoryQueryService categoryQueryService;
	private final MemberRepository memberRepository;
	private final MemberInterestCategoryRepository memberInterestCategoryRepository;
	private final ProductImageStorage productImageStorage;
	private final ImageUrlService imageUrlService;
	private final CategoryRecommendationService categoryRecommendationService;
	private final AiPriceRecommendationClient aiPriceRecommendationClient;
	private final ObjectMapper objectMapper;
	private final ApplicationEventPublisher applicationEventPublisher;

	@Transactional
	public UploadProductImageResponse uploadImage(Long memberId, MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new InvalidProductRequestException("이미지 파일은 필수입니다.");
		}

		loadActiveMember(memberId);
		String originalFilename = file.getOriginalFilename() == null ? "image.jpg" : file.getOriginalFilename().trim();
		ProductImage savedImage = productImageRepository.save(ProductImage.createTemporary("", originalFilename, memberId));

		String storedFileName = productImageStorage.store(savedImage.getImageId(), file, originalFilename);
		savedImage.updateImageUrl(imageUrlService.toProductImagePath(storedFileName));
		return new UploadProductImageResponse(savedImage.getImageId(), imageUrlService.toPublicUrl(savedImage.getImageUrl()));
	}

	@Transactional
	public DeleteProductImageResponse deleteImage(Long memberId, Long imageId) {
		ProductImage image = productImageRepository.findByImageIdAndDeletedAtIsNull(imageId)
			.orElseThrow(() -> new ProductImageNotFoundException("존재하지 않는 이미지입니다."));

		if (!image.getMemberId().equals(memberId)) {
			throw new ProductAccessDeniedException("본인이 업로드한 이미지만 삭제할 수 있습니다.");
		}

		if (image.getProduct() != null) {
			throw new InvalidProductRequestException("이미 상품에 연결된 이미지는 삭제할 수 없습니다.");
		}

		productImageStorage.delete(image.getImageUrl());
		image.softDelete();
		return new DeleteProductImageResponse(image.getImageId(), true);
	}

	public MySellingProductListResponse getMySellingProducts(Long memberId, int page, int size) {
		loadActiveMember(memberId);
		int normalizedPage = Math.max(page, 0);
		int normalizedSize = Math.min(Math.max(size, 1), 100);
		Page<Product> productPage = productRepository.findAllByMemberIdAndStatusAndDeletedAtIsNull(
			memberId,
			ProductStatus.ON_SALE,
			PageRequest.of(normalizedPage, normalizedSize, Sort.by(Sort.Direction.DESC, "createdAt"))
		);

		Map<Long, String> categoryNamesById = loadCategoryNames(productPage.getContent());
		List<MySellingProductItemResponse> content = productPage.getContent().stream()
			.map(product -> toMySellingProductItemResponse(product, categoryNamesById))
			.toList();

		return new MySellingProductListResponse(
			content,
			productPage.getNumber(),
			productPage.getSize(),
			productPage.getTotalElements(),
			productPage.hasNext()
		);
	}

	public PopularProductListResponse getPopularProducts(int size) {
		int normalizedSize = Math.min(Math.max(size, 1), 100);
		List<Product> products = productRepository.findAllBySaleTypeAndStatusAndDeletedAtIsNull(
			ProductSaleType.REGULAR,
			ProductStatus.ON_SALE
		);

		Map<Long, String> categoryNamesById = loadCategoryNames(products);
		List<PopularProductItemResponse> content = products.stream()
			.map(product -> toPopularProductItemResponse(product, categoryNamesById))
			.sorted(Comparator.comparingDouble(PopularProductItemResponse::popularScore).reversed()
				.thenComparing(PopularProductItemResponse::viewCount, Comparator.reverseOrder())
				.thenComparing(PopularProductItemResponse::createdAt, Comparator.reverseOrder()))
			.limit(normalizedSize)
			.toList();

		return new PopularProductListResponse(content);
	}

	public HotListProductListResponse getHotListProducts(Long memberId, int size) {
		int normalizedSize = Math.min(Math.max(size, 1), 100);
		List<Product> products = loadHotListCandidateProducts(memberId);

		Map<Long, String> categoryNamesById = loadCategoryNames(products);
		List<Product> rankedProducts = products.stream()
			.sorted(Comparator.comparingDouble(this::calculateHotScore).reversed()
				.thenComparing(Product::getFavoriteCount, Comparator.reverseOrder())
				.thenComparing(Product::getViewCount, Comparator.reverseOrder())
				.thenComparing(Product::getCreatedAt, Comparator.reverseOrder()))
			.limit(normalizedSize)
			.toList();

		List<HotListProductItemResponse> content = new ArrayList<>();
		for (int index = 0; index < rankedProducts.size(); index++) {
			Product product = rankedProducts.get(index);
			content.add(toHotListProductItemResponse(product, categoryNamesById, index + 1));
		}

		return new HotListProductListResponse(content);
	}

	private List<Product> loadHotListCandidateProducts(Long memberId) {
		Set<Long> interestCategoryIds = resolveInterestCategoryIds(memberId);
		if (interestCategoryIds.isEmpty()) {
			return productRepository.findAllBySaleTypeAndStatusAndDeletedAtIsNull(
				ProductSaleType.REGULAR,
				ProductStatus.ON_SALE
			);
		}

		return productRepository.findAllBySaleTypeAndStatusAndDeletedAtIsNullAndCategoryIdIn(
			ProductSaleType.REGULAR,
			ProductStatus.ON_SALE,
			interestCategoryIds
		);
	}

	private Set<Long> resolveInterestCategoryIds(Long memberId) {
		List<Long> topLevelCategoryIds = memberInterestCategoryRepository
			.findAllByMemberIdOrderByCategoryIdAsc(memberId)
			.stream()
			.map(memberInterestCategory -> memberInterestCategory.getCategoryId())
			.toList();
		if (topLevelCategoryIds.isEmpty()) {
			return Set.of();
		}

		Set<Long> categoryIds = new LinkedHashSet<>();
		categoryRepository.findAllById(topLevelCategoryIds)
			.forEach(category -> categoryIds.addAll(resolveSearchableCategoryIds(category)));
		return categoryIds;
	}

	public ProductEditDetailResponse getProductEditDetail(Long memberId, Long productId) {
		loadActiveMember(memberId);
		Product product = loadOwnedProduct(memberId, productId);
		Category category = categoryRepository.findById(product.getCategoryId()).orElse(null);

		List<ProductEditImageResponse> images = product.getImages().stream()
			.filter(image -> image.getImageUrl() != null && !image.getImageUrl().isBlank())
			.sorted(Comparator.comparing(ProductImage::getSortOrder, Comparator.nullsLast(Integer::compareTo))
				.thenComparing(ProductImage::getCreatedAt))
			.map(image -> new ProductEditImageResponse(
				image.getImageId(),
				imageUrlService.toPublicUrl(image.getImageUrl()),
				image.getSortOrder()
			))
			.toList();

		return new ProductEditDetailResponse(
			product.getProductId(),
			product.getName(),
			product.getDescription(),
			product.getCategoryId(),
			category == null ? "" : category.getNameKo(),
			product.getLocation(),
			images,
			product.getPrice(),
			product.isAllowOffer(),
			product.getStatus(),
			product.getStatus() == ProductStatus.ON_SALE
		);
	}

	@Transactional
	public ProductEditDetailResponse updateProduct(Long memberId, Long productId, UpdateProductRequest request) {
		loadActiveMember(memberId);
		Product product = loadOwnedProduct(memberId, productId);
		if (product.getStatus() != ProductStatus.ON_SALE) {
			throw new InvalidProductRequestException("판매 중인 상품만 수정할 수 있습니다.");
		}

		validateCategorySelection(request.categoryId());
		product.updateEditableDetails(
			request.name().trim(),
			request.description().trim(),
			request.categoryId(),
			request.price(),
			request.location().trim()
		);
		product.updateAllowOffer(request.allowOffer());
		replaceProductImages(product, request.images());
		long searchVersion = product.increaseSearchVersion();
		applicationEventPublisher.publishEvent(new ProductSearchIndexRequestedEvent(product.getProductId(), searchVersion));

		return getProductEditDetail(memberId, productId);
	}

	@Transactional
	public void deleteProduct(Long memberId, Long productId) {
		Product product = loadOwnedProduct(memberId, productId);
		if (product.getStatus() != ProductStatus.ON_SALE) {
			throw new InvalidProductRequestException("판매 중인 상품만 삭제할 수 있습니다.");
		}

		for (ProductImage image : product.getImages()) {
			productImageStorage.delete(image.getImageUrl());
		}
		product.softDeleteWithImages();
		applicationEventPublisher.publishEvent(new ProductSearchDeleteRequestedEvent(product.getProductId()));
	}

	@Transactional
	public SaveProductDraftResponse saveDraft(Long memberId, SaveProductDraftRequest request) {
		validateDraftRequest(request);
		Member member = loadVerifiedMember(memberId);
		String location = resolveLocation(member, request.location());

		OffsetDateTime savedAt = OffsetDateTime.now(ZoneOffset.UTC);
		String payloadJson = serializeDraft(request);

		ProductDraft draft = request.draftId() == null
			? ProductDraft.create(
				normalizeBlank(request.name()),
				normalizeBlank(request.description()),
				request.saleType(),
				request.categoryId(),
				member.getMemberId(),
				request.price(),
				request.allowOffer(),
				location,
				payloadJson,
				savedAt
			)
			: productDraftRepository.findById(request.draftId())
				.map(existingDraft -> {
					existingDraft.update(
						normalizeBlank(request.name()),
						normalizeBlank(request.description()),
						request.saleType(),
						request.categoryId(),
						member.getMemberId(),
						request.price(),
						request.allowOffer(),
						location,
						payloadJson,
						savedAt
					);
					return existingDraft;
				})
				.orElseGet(() -> ProductDraft.create(
					normalizeBlank(request.name()),
					normalizeBlank(request.description()),
					request.saleType(),
					request.categoryId(),
					member.getMemberId(),
					request.price(),
					request.allowOffer(),
					location,
					payloadJson,
					savedAt
				));

		ProductDraft savedDraft = productDraftRepository.save(draft);
		return new SaveProductDraftResponse(savedDraft.getDraftId(), savedDraft.getSavedAt());
	}

	public RecommendCategoryResponse recommendCategory(RecommendCategoryRequest request) {
		CategoryRecommendationResult result = categoryRecommendationService.recommend(
			request.name(),
			request.description(),
			request.topCategoryId(),
			request.imageUrls()
		);
		return RecommendCategoryResponse.from(result);
	}

	public List<CategoryOptionResponse> getCategories() {
		List<Category> categories = categoryQueryService.findAllOrdered();
		Map<Long, CategoryNode> nodesById = new LinkedHashMap<>();

		for (Category category : categories) {
			nodesById.put(category.getId(), new CategoryNode(category));
		}

		List<CategoryNode> roots = new ArrayList<>();
		for (Category category : categories) {
			CategoryNode node = nodesById.get(category.getId());
			if (category.getParentId() == null) {
				roots.add(node);
				continue;
			}

			CategoryNode parent = nodesById.get(category.getParentId());
			if (parent != null) {
				parent.children().add(node);
			}
		}

		return roots.stream().map(this::toCategoryResponse).toList();
	}

	public List<SearchCategoryOptionResponse> getSearchCategories() {
		List<Category> categories = categoryQueryService.findAllOrdered();
		Map<Long, CategoryNode> nodesById = buildCategoryNodesById(categories);
		List<CategoryNode> roots = connectCategoryNodes(categories, nodesById);
		Set<Long> activeLeafCategoryIds = new LinkedHashSet<>(
			productRepository.findDistinctCategoryIdsBySaleTypeAndStatusAndDeletedAtIsNull(
				ProductSaleType.REGULAR,
				ProductStatus.ON_SALE
			)
		);

		return roots.stream()
			.map(root -> toSearchCategoryResponse(root, activeLeafCategoryIds))
			.toList();
	}

	public ProductSearchListResponse searchProductsByCategory(Long categoryId, int page, int size) {
		Category selectedCategory = categoryRepository.findById(categoryId)
			.orElseThrow(() -> new InvalidProductRequestException("존재하지 않는 카테고리입니다."));

		int normalizedPage = Math.max(page, 0);
		int normalizedSize = Math.min(Math.max(size, 1), 100);
		Set<Long> searchableCategoryIds = resolveSearchableCategoryIds(selectedCategory);

		Page<Product> productPage = productRepository.findAllBySaleTypeAndStatusAndDeletedAtIsNullAndCategoryIdIn(
			ProductSaleType.REGULAR,
			ProductStatus.ON_SALE,
			searchableCategoryIds,
			PageRequest.of(normalizedPage, normalizedSize, Sort.by(Sort.Direction.DESC, "createdAt"))
		);

		Map<Long, String> categoryNamesById = loadCategoryNames(productPage.getContent());
		List<ProductSearchItemResponse> content = productPage.getContent().stream()
			.map(product -> toProductSearchItemResponse(product, categoryNamesById))
			.toList();

		return new ProductSearchListResponse(
			content,
			productPage.getNumber(),
			productPage.getSize(),
			productPage.getTotalElements(),
			productPage.hasNext()
		);
	}

	public RecommendPriceResponse recommendPrice(RecommendPriceRequest request) {
		if (request.saleType() != ProductSaleType.REGULAR) {
			throw new InvalidProductRequestException("일반 상품 가격 추천에서는 saleType이 REGULAR여야 합니다.");
		}

		AiPriceRecommendationResponse response = aiPriceRecommendationClient.recommend(
			AiPriceRecommendationRequest.of(
				request.name().trim(),
				request.description().trim(),
				request.saleType().name()
			)
		);
		return new RecommendPriceResponse(BigDecimal.valueOf(response.suggestedPrice()));
	}

	@Transactional
	public CreateProductResponse createProduct(Long memberId, CreateProductRequest request) {
		validateCreateRequest(request);
		validateCategorySelection(request.categoryId());
		Member member = loadVerifiedMember(memberId);
		String location = resolveLocation(member, request.location());

		Product product = productRepository.save(
			Product.create(
				request.name().trim(),
				request.description().trim(),
				request.saleType(),
				request.categoryId(),
				member.getMemberId(),
				request.price(),
				request.allowOffer(),
				location,
				request.draftId(),
				ProductStatus.ON_SALE
			)
		);

		Map<Long, ProductImage> imagesById = loadRequestedImages(request.images());
		for (ProductImagePayload imagePayload : request.images()) {
			ProductImage image = imagesById.get(imagePayload.imageId());
			product.attachImage(image, imagePayload.sortOrder());
		}
		productImageRepository.saveAll(imagesById.values());
		applicationEventPublisher.publishEvent(new ProductSearchIndexRequestedEvent(product.getProductId(), product.getSearchVersion()));

		return new CreateProductResponse(
			product.getProductId(),
			product.getSaleType(),
			product.getStatus(),
			null,
			new CreateProductResponse.GeneralSaleResponse(product.getPrice())
		);
	}

	private Map<Long, ProductImage> loadRequestedImages(List<ProductImagePayload> imagePayloads) {
		List<Long> imageIds = imagePayloads.stream().map(ProductImagePayload::imageId).toList();

		List<ProductImage> images = productImageRepository.findAllByImageIdInAndDeletedAtIsNull(imageIds);
		if (images.size() != imageIds.size()) {
			throw new ProductImageNotFoundException("존재하지 않는 이미지가 포함되어 있습니다.");
		}

		Map<Long, ProductImage> imagesById = new LinkedHashMap<>();
		for (ProductImage image : images) {
			if (image.getProduct() != null) {
				throw new InvalidProductRequestException("이미 다른 상품에 연결된 이미지가 포함되어 있습니다.");
			}
			imagesById.put(image.getImageId(), image);
		}

		validateDuplicateImageIds(imageIds, imagesById.keySet());
		return imagesById;
	}

	private Map<Long, ProductImage> loadRequestedImagesForUpdate(Product product, List<ProductImagePayload> imagePayloads) {
		List<Long> imageIds = imagePayloads.stream().map(ProductImagePayload::imageId).toList();

		List<ProductImage> images = productImageRepository.findAllByImageIdInAndDeletedAtIsNull(imageIds);
		if (images.size() != imageIds.size()) {
			throw new ProductImageNotFoundException("상품이미지를 찾을 수 없습니다.");
		}

		Map<Long, ProductImage> imagesById = new LinkedHashMap<>();
		for (ProductImage image : images) {
			if (!image.getMemberId().equals(product.getMemberId())) {
				throw new ProductAccessDeniedException("본인이 업로드한 이미지로만 수정할 수 있습니다.");
			}
			if (image.getProduct() != null && !image.getProduct().getProductId().equals(product.getProductId())) {
				throw new InvalidProductRequestException("이미 다른 상품에 연결된 이미지가 포함되어 있습니다.");
			}
			imagesById.put(image.getImageId(), image);
		}
		validateDuplicateImageIds(imageIds, imagesById.keySet());
		return imagesById;
	}

	private void validateDuplicateImageIds(List<Long> imageIds, Collection<Long> storedImageIds) {
		if (storedImageIds.size() != imageIds.size()) {
			throw new InvalidProductRequestException("중복된 이미지 ID는 사용할 수 없습니다.");
		}
	}

	private void validateCategorySelection(Long categoryId) {
		Category category = categoryRepository.findById(categoryId)
			.orElseThrow(() -> new InvalidProductRequestException("존재하지 않는 카테고리입니다."));

		if (category.getDepth() == null || category.getDepth() != 3) {
			throw new InvalidProductRequestException("최하위 카테고리만 선택할 수 있습니다.");
		}
	}

	private void validateCreateRequest(CreateProductRequest request) {
		if (request.saleType() != ProductSaleType.REGULAR) {
			throw new InvalidProductRequestException("일반 상품 등록에서는 saleType이 REGULAR여야 합니다.");
		}
		if (request.price().signum() <= 0) {
			throw new InvalidProductRequestException("판매 가격은 0보다 커야 합니다.");
		}
	}

	private void validateDraftRequest(SaveProductDraftRequest request) {
		boolean hasSaleType = request.saleType() != null;
		boolean hasName = normalizeBlank(request.name()) != null;
		boolean hasDescription = normalizeBlank(request.description()) != null;
		boolean hasCategory = request.categoryId() != null;
		boolean hasPrice = request.price() != null;
		boolean hasAllowOffer = request.allowOffer() != null;
		boolean hasImages = request.images() != null && !request.images().isEmpty();
		boolean hasLocation = normalizeBlank(request.location()) != null;

		if (!hasSaleType && !hasName && !hasDescription && !hasCategory && !hasPrice && !hasAllowOffer && !hasImages && !hasLocation) {
			throw new InvalidProductRequestException("임시저장할 내용이 없습니다.");
		}
		if (request.saleType() != null && request.saleType() != ProductSaleType.REGULAR) {
			throw new InvalidProductRequestException("일반 상품 임시저장에서는 saleType이 REGULAR여야 합니다.");
		}
		if (request.price() != null && request.price().signum() <= 0) {
			throw new InvalidProductRequestException("판매 가격은 0보다 커야 합니다.");
		}
	}

	private Member loadActiveMember(Long memberId) {
		return memberRepository.findByMemberIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new InvalidCredentialsException("존재하지 않는 회원입니다."));
	}

	private Product loadOwnedProduct(Long memberId, Long productId) {
		Product product = productRepository.findByProductIdAndMemberIdAndDeletedAtIsNull(productId, memberId)
			.orElseThrow(() -> new InvalidProductRequestException("존재하지 않는 상품입니다."));
		if (!product.getMemberId().equals(memberId)) {
			throw new ProductAccessDeniedException("본인 상품만 조회하거나 수정할 수 있습니다.");
		}
		return product;
	}

	private Member loadVerifiedMember(Long memberId) {
		Member member = loadActiveMember(memberId);
		if (!member.isVerified()) {
			throw new EmailNotVerifiedException();
		}
		return member;
	}

	private String resolveLocation(Member member, String requestLocation) {
		String location = normalizeBlank(requestLocation);
		if (location != null) {
			return location;
		}

		String memberLocation = normalizeBlank(member.getLocation());
		if (memberLocation == null) {
			throw new InvalidProductRequestException("거래 위치는 필수입니다.");
		}

		return memberLocation;
	}

	private String serializeDraft(SaveProductDraftRequest request) {
		try {
			return objectMapper.writeValueAsString(request);
		} catch (JsonProcessingException exception) {
			throw new InvalidProductRequestException("임시저장 데이터를 처리하는 중 오류가 발생했습니다.");
		}
	}

	private String normalizeBlank(String value) {
		if (value == null) {
			return null;
		}

		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private CategoryOptionResponse toCategoryResponse(CategoryNode node) {
		Category category = node.category();
		return new CategoryOptionResponse(
			category.getId(),
			category.getNameKo(),
			category.getNameEn(),
			category.getDepth(),
			category.getParentId(),
			node.children().stream().map(this::toCategoryResponse).toList()
		);
	}

	private SearchCategoryOptionResponse toSearchCategoryResponse(CategoryNode node, Set<Long> activeLeafCategoryIds) {
		Category category = node.category();
		List<SearchCategoryOptionResponse> children = node.children().stream()
			.map(child -> toSearchCategoryResponse(child, activeLeafCategoryIds))
			.toList();
		boolean enabled = activeLeafCategoryIds.contains(category.getId())
			|| children.stream().anyMatch(SearchCategoryOptionResponse::enabled);

		return new SearchCategoryOptionResponse(
			category.getId(),
			category.getNameKo(),
			category.getNameEn(),
			category.getDepth(),
			category.getParentId(),
			enabled,
			children
		);
	}

	private Map<Long, CategoryNode> buildCategoryNodesById(List<Category> categories) {
		Map<Long, CategoryNode> nodesById = new LinkedHashMap<>();
		for (Category category : categories) {
			nodesById.put(category.getId(), new CategoryNode(category));
		}
		return nodesById;
	}

	private List<CategoryNode> connectCategoryNodes(List<Category> categories, Map<Long, CategoryNode> nodesById) {
		List<CategoryNode> roots = new ArrayList<>();
		for (Category category : categories) {
			CategoryNode node = nodesById.get(category.getId());
			if (category.getParentId() == null) {
				roots.add(node);
				continue;
			}

			CategoryNode parent = nodesById.get(category.getParentId());
			if (parent != null) {
				parent.children().add(node);
			}
		}
		return roots;
	}

	private Set<Long> resolveSearchableCategoryIds(Category selectedCategory) {
		List<Category> categories = categoryQueryService.findAllOrdered();
		Map<Long, CategoryNode> nodesById = buildCategoryNodesById(categories);
		connectCategoryNodes(categories, nodesById);

		CategoryNode selectedNode = nodesById.get(selectedCategory.getId());
		Set<Long> categoryIds = new LinkedHashSet<>();
		collectCategoryIds(selectedNode, categoryIds);
		return categoryIds;
	}

	private void collectCategoryIds(CategoryNode node, Set<Long> categoryIds) {
		if (node == null) {
			return;
		}

		categoryIds.add(node.category().getId());
		for (CategoryNode child : node.children()) {
			collectCategoryIds(child, categoryIds);
		}
	}

	private Map<Long, String> loadCategoryNames(List<Product> products) {
		Set<Long> categoryIds = products.stream()
			.map(Product::getCategoryId)
			.collect(java.util.stream.Collectors.toSet());

		Map<Long, String> categoryNamesById = new HashMap<>();
		categoryRepository.findAllById(categoryIds)
			.forEach(category -> categoryNamesById.put(category.getId(), category.getNameKo()));
		return categoryNamesById;
	}

	private String resolveThumbnailUrl(Product product) {
		return product.getImages().stream()
			.filter(image -> image.getImageUrl() != null && !image.getImageUrl().isBlank())
			.sorted(Comparator.comparing(ProductImage::getSortOrder, Comparator.nullsLast(Integer::compareTo))
				.thenComparing(ProductImage::getCreatedAt))
			.map(image -> imageUrlService.toPublicUrl(image.getImageUrl()))
			.findFirst()
			.orElse(null);
	}

	private MySellingProductItemResponse toMySellingProductItemResponse(
		Product product,
		Map<Long, String> categoryNamesById
	) {
		return new MySellingProductItemResponse(
			product.getProductId(),
			product.getName(),
			product.getDescription(),
			categoryNamesById.getOrDefault(product.getCategoryId(), ""),
			resolveThumbnailUrl(product),
			product.getStatus(),
			product.getPrice(),
			product.getLocation(),
			product.getStatus() == ProductStatus.ON_SALE,
			product.getStatus() == ProductStatus.ON_SALE,
			toSeoulOffsetDateTime(product.getCreatedAt()),
			toSeoulOffsetDateTime(product.getUpdatedAt() == null ? product.getCreatedAt() : product.getUpdatedAt())
		);
	}

	private void replaceProductImages(Product product, List<ProductImagePayload> imagePayloads) {
		Map<Long, ProductImage> imagesById = loadRequestedImagesForUpdate(product, imagePayloads);
		List<ProductImage> orderedImages = imagePayloads.stream()
			.map(imagePayload -> {
				ProductImage image = imagesById.get(imagePayload.imageId());
				image.assignToProduct(product, imagePayload.sortOrder());
				return image;
			})
			.toList();

		List<ProductImage> removedImages = product.getImages().stream()
			.filter(image -> !orderedImages.contains(image))
			.toList();

		product.replaceImages(orderedImages);
		for (ProductImage removedImage : removedImages) {
			productImageStorage.delete(removedImage.getImageUrl());
			removedImage.softDelete();
		}
		productImageRepository.saveAll(imagesById.values());
		productImageRepository.saveAll(removedImages);
	}

	private PopularProductItemResponse toPopularProductItemResponse(
		Product product,
		Map<Long, String> categoryNamesById
	) {
		return new PopularProductItemResponse(
			product.getProductId(),
			product.getName(),
			resolveThumbnailUrl(product),
			product.getPrice(),
			product.getLocation(),
			categoryNamesById.getOrDefault(product.getCategoryId(), ""),
			product.getViewCount(),
			toSeoulOffsetDateTime(product.getCreatedAt()),
			calculatePopularScore(product)
		);
	}

	private HotListProductItemResponse toHotListProductItemResponse(
		Product product,
		Map<Long, String> categoryNamesById,
		int rank
	) {
		return new HotListProductItemResponse(
			product.getProductId(),
			product.getName(),
			resolveThumbnailUrl(product),
			product.getPrice(),
			product.getLocation(),
			categoryNamesById.getOrDefault(product.getCategoryId(), ""),
			product.getViewCount(),
			product.getFavoriteCount(),
			calculateHotScore(product),
			rank,
			toSeoulOffsetDateTime(product.getCreatedAt())
		);
	}

	private ProductSearchItemResponse toProductSearchItemResponse(
		Product product,
		Map<Long, String> categoryNamesById
	) {
		return new ProductSearchItemResponse(
			product.getProductId(),
			product.getName(),
			resolveThumbnailUrl(product),
			product.getPrice(),
			product.getLocation(),
			product.getCategoryId(),
			categoryNamesById.getOrDefault(product.getCategoryId(), ""),
			product.getViewCount(),
			product.getFavoriteCount(),
			toSeoulOffsetDateTime(product.getCreatedAt())
		);
	}

	private double calculatePopularScore(Product product) {
		if (product.getViewCount() <= 0) {
			return 0.0;
		}

		LocalDateTime createdAt = product.getCreatedAt();
		if (createdAt == null) {
			return (double) product.getViewCount();
		}

		long elapsedHours = Math.max(1L, Duration.between(createdAt, LocalDateTime.now(SEOUL_ZONE)).toHours());
		return (double) product.getViewCount() / elapsedHours;
	}

	private double calculateHotScore(Product product) {
		long baseScore = product.getViewCount() + product.getFavoriteCount() * 3;
		if (baseScore <= 0) {
			return 0.0;
		}

		LocalDateTime createdAt = product.getCreatedAt();
		if (createdAt == null) {
			return (double) baseScore;
		}

		long elapsedHours = Math.max(1L, Duration.between(createdAt, LocalDateTime.now(SEOUL_ZONE)).toHours());
		return (double) baseScore / elapsedHours;
	}

	private OffsetDateTime toSeoulOffsetDateTime(LocalDateTime dateTime) {
		if (dateTime == null) {
			return null;
		}
		return dateTime.atZone(SEOUL_ZONE).toOffsetDateTime();
	}

	private record CategoryNode(
		Category category,
		List<CategoryNode> children
	) {
		private CategoryNode(Category category) {
			this(category, new ArrayList<>());
		}
	}
}
