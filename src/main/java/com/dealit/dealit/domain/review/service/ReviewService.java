package com.dealit.dealit.domain.review.service;

import com.dealit.dealit.domain.auction.AuctionStatus;
import com.dealit.dealit.domain.auction.entity.Auction;
import com.dealit.dealit.domain.auction.repository.AuctionRepository;
import com.dealit.dealit.domain.auth.exception.InvalidCredentialsException;
import com.dealit.dealit.domain.member.entity.Member;
import com.dealit.dealit.domain.member.repository.MemberRepository;
import com.dealit.dealit.domain.notification.dto.NotificationCreateRequest;
import com.dealit.dealit.domain.notification.entity.InAppNotificationType;
import com.dealit.dealit.domain.notification.service.FcmNotificationService;
import com.dealit.dealit.domain.notification.service.NotificationCenterService;
import com.dealit.dealit.domain.product.ProductSaleType;
import com.dealit.dealit.domain.product.entity.Product;
import com.dealit.dealit.domain.product.repository.ProductRepository;
import com.dealit.dealit.domain.purchase.entity.PurchaseStatus;
import com.dealit.dealit.domain.purchase.repository.PurchaseRepository;
import com.dealit.dealit.domain.review.dto.CreateReviewRequest;
import com.dealit.dealit.domain.review.dto.ReviewListResponse;
import com.dealit.dealit.domain.review.dto.ReviewRatingSummaryResponse;
import com.dealit.dealit.domain.review.dto.ReviewResponse;
import com.dealit.dealit.domain.review.entity.Review;
import com.dealit.dealit.domain.review.repository.ReviewRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

	private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
	private static final String REVIEW_TARGET_TYPE = "REVIEW";

	private final ReviewRepository reviewRepository;
	private final ProductRepository productRepository;
	private final AuctionRepository auctionRepository;
	private final MemberRepository memberRepository;
	private final PurchaseRepository purchaseRepository;
	private final NotificationCenterService notificationCenterService;
	private final FcmNotificationService fcmNotificationService;

	@Transactional
	public ReviewResponse createReview(Long reviewerId, CreateReviewRequest request) {
		Member reviewer = loadActiveMember(reviewerId);
		validateRatingUnit(request.rating());
		ReviewTarget target = resolveReviewTarget(reviewer.getMemberId(), request);

		if (reviewer.getMemberId().equals(target.revieweeId())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot review yourself.");
		}
		validateDuplicateReview(reviewer.getMemberId(), target);

		Review review = reviewRepository.save(Review.create(
			reviewer.getMemberId(),
			target.revieweeId(),
			target.product().getProductId(),
			target.auctionId(),
			request.rating().setScale(1, RoundingMode.UNNECESSARY),
			request.content().trim()
		));
		notifyReviewReceived(review, target.product());

		Map<Long, Member> membersById = loadMembersById(Set.of(review.getReviewerId(), review.getRevieweeId()));
		Map<Long, Product> productsById = loadProductsById(Set.of(review.getProductId()));
		return toReviewResponse(review, membersById, productsById);
	}

	public ReviewListResponse getReceivedReviews(Long revieweeId, int page, int size) {
		loadActiveMember(revieweeId);
		Page<Review> reviewPage = reviewRepository.findAllByRevieweeIdAndDeletedAtIsNull(
			revieweeId,
			toPageRequest(page, size)
		);
		return toListResponse(reviewPage, revieweeId);
	}

	public ReviewListResponse getWrittenReviews(Long reviewerId, int page, int size) {
		loadActiveMember(reviewerId);
		Page<Review> reviewPage = reviewRepository.findAllByReviewerIdAndDeletedAtIsNull(
			reviewerId,
			toPageRequest(page, size)
		);
		return toListResponse(reviewPage, reviewerId);
	}

	private ReviewTarget resolveReviewTarget(Long reviewerId, CreateReviewRequest request) {
		boolean hasProductId = request.productId() != null;
		boolean hasAuctionId = request.auctionId() != null;
		if (hasProductId == hasAuctionId) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Either productId or auctionId must be provided.");
		}
		if (hasAuctionId) {
			return resolveAuctionReviewTarget(reviewerId, request.auctionId());
		}
		return resolveRegularProductReviewTarget(reviewerId, request.productId());
	}

	private void validateRatingUnit(BigDecimal rating) {
		BigDecimal doubled = rating.stripTrailingZeros().multiply(BigDecimal.valueOf(2));
		if (doubled.setScale(0, RoundingMode.DOWN).compareTo(doubled) != 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rating must increase by 0.5.");
		}
	}

	private ReviewTarget resolveAuctionReviewTarget(Long reviewerId, Long auctionId) {
		Auction auction = auctionRepository.findDetailByAuctionIdAndDeletedAtIsNullAndProductDeletedAtIsNull(auctionId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Auction not found."));
		Product product = auction.getProduct();
		if (auction.getStatus() != AuctionStatus.SUCCESSFUL_BID) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only successful auctions can be reviewed.");
		}
		if (!reviewerId.equals(auction.getWinnerId())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the winning bidder can review this auction.");
		}
		if (!existsCompletedPurchase(product.getProductId(), product.getMemberId(), reviewerId)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the completed buyer can review this auction.");
		}
		return new ReviewTarget(product, auction.getAuctionId(), product.getMemberId());
	}

	private ReviewTarget resolveRegularProductReviewTarget(Long reviewerId, Long productId) {
		Product product = productRepository.findByProductIdAndDeletedAtIsNull(productId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product not found."));
		if (product.getSaleType() != ProductSaleType.REGULAR) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Auction products must be reviewed with auctionId.");
		}
		if (!existsCompletedPurchase(product.getProductId(), product.getMemberId(), reviewerId)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the completed buyer can review this product.");
		}
		return new ReviewTarget(product, null, product.getMemberId());
	}

	private boolean existsCompletedPurchase(Long productId, Long sellerId, Long buyerId) {
		return purchaseRepository.existsByProductIdAndSellerIdAndBuyerIdAndStatusAndDeletedAtIsNull(
			productId,
			sellerId,
			buyerId,
			PurchaseStatus.COMPLETED
		);
	}

	private void validateDuplicateReview(Long reviewerId, ReviewTarget target) {
		boolean exists = target.auctionId() == null
			? reviewRepository.existsByReviewerIdAndProductIdAndAuctionIdIsNullAndDeletedAtIsNull(
				reviewerId,
				target.product().getProductId()
			)
			: reviewRepository.existsByReviewerIdAndAuctionIdAndDeletedAtIsNull(reviewerId, target.auctionId());
		if (exists) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Review already exists.");
		}
	}

	private void notifyReviewReceived(Review review, Product product) {
		String title = "리뷰가 등록되었습니다.";
		String content = "'" + product.getName() + "' 구매자가 거래 리뷰를 작성했어요.";
		String targetUrl = "/mypage/review";

		notificationCenterService.create(
			review.getRevieweeId(),
			new NotificationCreateRequest(
				InAppNotificationType.TRADE,
				title,
				content,
				REVIEW_TARGET_TYPE,
				review.getReviewId(),
				targetUrl
			)
		);

		try {
			int sentCount = fcmNotificationService.sendToMember(
				review.getRevieweeId(),
				title,
				content,
				Map.of(
					"type", "REVIEW_RECEIVED",
					"reviewId", String.valueOf(review.getReviewId()),
					"productId", String.valueOf(review.getProductId()),
					"targetUrl", targetUrl
				)
			);
			log.debug("Sent review push notification. reviewId={}, revieweeId={}, sentCount={}",
				review.getReviewId(), review.getRevieweeId(), sentCount);
		} catch (RuntimeException exception) {
			log.warn("Failed to send review push notification. reviewId={}, revieweeId={}",
				review.getReviewId(), review.getRevieweeId(), exception);
		}
	}

	private ReviewListResponse toListResponse(Page<Review> reviewPage, Long ratingOwnerId) {
		Set<Long> memberIds = new HashSet<>();
		Set<Long> productIds = new HashSet<>();
		for (Review review : reviewPage.getContent()) {
			memberIds.add(review.getReviewerId());
			memberIds.add(review.getRevieweeId());
			productIds.add(review.getProductId());
		}

		Map<Long, Member> membersById = loadMembersById(memberIds);
		Map<Long, Product> productsById = loadProductsById(productIds);
		return new ReviewListResponse(
			toRatingSummary(ratingOwnerId),
			reviewPage.getContent().stream()
				.map(review -> toReviewResponse(review, membersById, productsById))
				.toList(),
			reviewPage.getNumber(),
			reviewPage.getSize(),
			reviewPage.getTotalElements(),
			reviewPage.hasNext()
		);
	}

	private ReviewRatingSummaryResponse toRatingSummary(Long memberId) {
		ReviewRatingSummaryResponse summary = reviewRepository.getRatingSummaryByRevieweeId(memberId);
		return new ReviewRatingSummaryResponse(
			memberId,
			roundToOneDecimal(summary.averageRating()),
			summary.reviewCount()
		);
	}

	private ReviewResponse toReviewResponse(
		Review review,
		Map<Long, Member> membersById,
		Map<Long, Product> productsById
	) {
		Member reviewer = membersById.get(review.getReviewerId());
		Member reviewee = membersById.get(review.getRevieweeId());
		Product product = productsById.get(review.getProductId());
		return new ReviewResponse(
			review.getReviewId(),
			review.getReviewerId(),
			resolveNickname(reviewer, review.getReviewerId()),
			review.getRevieweeId(),
			resolveNickname(reviewee, review.getRevieweeId()),
			review.getProductId(),
			product == null ? "" : product.getName(),
			review.getAuctionId(),
			review.getRating(),
			review.getContent(),
			toSeoulOffsetDateTime(review.getCreatedAt())
		);
	}

	private Member loadActiveMember(Long memberId) {
		return memberRepository.findByMemberIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new InvalidCredentialsException("Member not found."));
	}

	private Map<Long, Member> loadMembersById(Collection<Long> memberIds) {
		Map<Long, Member> membersById = new HashMap<>();
		if (memberIds.isEmpty()) {
			return membersById;
		}
		memberRepository.findAllByMemberIdInAndDeletedAtIsNull(memberIds)
			.forEach(member -> membersById.put(member.getMemberId(), member));
		return membersById;
	}

	private Map<Long, Product> loadProductsById(Collection<Long> productIds) {
		Map<Long, Product> productsById = new HashMap<>();
		if (productIds.isEmpty()) {
			return productsById;
		}
		productRepository.findAllById(productIds)
			.forEach(product -> productsById.put(product.getProductId(), product));
		return productsById;
	}

	private String resolveNickname(Member member, Long memberId) {
		if (member == null || member.getNickname() == null || member.getNickname().isBlank()) {
			return "User#" + memberId;
		}
		return member.getNickname();
	}

	private PageRequest toPageRequest(int page, int size) {
		int normalizedPage = Math.max(page, 0);
		int normalizedSize = Math.min(Math.max(size, 1), 100);
		return PageRequest.of(normalizedPage, normalizedSize, Sort.by(Sort.Direction.DESC, "createdAt"));
	}

	private double roundToOneDecimal(double value) {
		return Math.round(value * 10.0) / 10.0;
	}

	private OffsetDateTime toSeoulOffsetDateTime(LocalDateTime dateTime) {
		if (dateTime == null) {
			return null;
		}
		return dateTime.atZone(SEOUL_ZONE).toOffsetDateTime();
	}

	private record ReviewTarget(
		Product product,
		Long auctionId,
		Long revieweeId
	) {
	}
}
