package com.dealit.dealit.domain.auction.service;

import com.dealit.dealit.domain.auction.AuctionStatus;
import com.dealit.dealit.domain.auction.AuctionPaymentStatus;
import com.dealit.dealit.domain.auction.dto.AuctionDetailResponse;
import com.dealit.dealit.domain.auction.dto.AuctionDetailResponse.ImageResponse;
import com.dealit.dealit.domain.auction.dto.AuctionDetailResponse.SellerResponse;
import com.dealit.dealit.domain.auction.dto.AuctionBidHistoryResponse;
import com.dealit.dealit.domain.auction.dto.AuctionBidHistoryResponse.BidHistoryItem;
import com.dealit.dealit.domain.auction.dto.BidResponse;
import com.dealit.dealit.domain.auction.dto.BidResponse.BidMessages;
import com.dealit.dealit.domain.auction.entity.Auction;
import com.dealit.dealit.domain.auction.entity.AuctionPayment;
import com.dealit.dealit.domain.auction.entity.Category;
import com.dealit.dealit.domain.auction.entity.Bid;
import com.dealit.dealit.domain.auction.event.AuctionEventPublisher;
import com.dealit.dealit.domain.auction.event.AuctionRefundRequestedEvent;
import com.dealit.dealit.domain.auction.exception.AuctionBidException;
import com.dealit.dealit.domain.auction.exception.AuctionNotFoundException;
import com.dealit.dealit.domain.auction.exception.InvalidAuctionRequestException;
import com.dealit.dealit.domain.auction.redis.AuctionRedisService;
import com.dealit.dealit.domain.auction.redis.AuctionRedisService.AuctionState;
import com.dealit.dealit.domain.auction.redis.AuctionRedisService.BidScriptResult;
import com.dealit.dealit.domain.auction.repository.AuctionRepository;
import com.dealit.dealit.domain.auction.repository.AuctionPaymentRepository;
import com.dealit.dealit.domain.auction.repository.BidRepository;
import com.dealit.dealit.domain.auction.repository.CategoryRepository;
import com.dealit.dealit.domain.chat.entity.ChatRoom;
import com.dealit.dealit.domain.chat.entity.ChatType;
import com.dealit.dealit.domain.chat.repository.ChatRoomRepository;
import com.dealit.dealit.domain.member.entity.Member;
import com.dealit.dealit.domain.member.exception.EmailNotVerifiedException;
import com.dealit.dealit.domain.member.repository.MemberRepository;
import com.dealit.dealit.domain.product.entity.Product;
import com.dealit.dealit.domain.product.entity.ProductImage;
import com.dealit.dealit.domain.purchase.service.PurchaseService;
import com.dealit.dealit.domain.recentproduct.service.RecentProductService;
import com.dealit.dealit.domain.review.dto.ReviewRatingSummaryResponse;
import com.dealit.dealit.domain.review.repository.ReviewRepository;
import com.dealit.dealit.domain.search.event.AuctionSearchDeleteRequestedEvent;
import com.dealit.dealit.domain.search.event.AuctionSearchIndexRequestedEvent;
import com.dealit.dealit.domain.wallet.service.WalletService;
import com.dealit.dealit.domain.wishlist.service.WishlistService;
import com.dealit.dealit.global.service.ImageUrlService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuctionBidService {

	private final AuctionRepository auctionRepository;
	private final BidRepository bidRepository;
	private final AuctionPaymentRepository auctionPaymentRepository;
	private final CategoryRepository categoryRepository;
	private final MemberRepository memberRepository;
	private final WalletService walletService;
	private final AuctionRedisService auctionRedisService;
	private final AuctionEventPublisher auctionEventPublisher;
	private final AuctionNotificationService auctionNotificationService;
	private final ApplicationEventPublisher applicationEventPublisher;
	private final ChatRoomRepository chatRoomRepository;
	private final PurchaseService purchaseService;
	private final WishlistService wishlistService;
	private final ImageUrlService imageUrlService;
	private final RecentProductService recentProductService;
	private final ReviewRepository reviewRepository;
	private final Clock clock;

	public AuctionDetailResponse getAuction(Long auctionId) {
		return getAuction(auctionId, null);
	}

	public AuctionDetailResponse getAuction(Long auctionId, Long memberId) {
		Auction auction = loadAuctionDetail(auctionId);
		recentProductService.recordAuction(memberId, auction.getAuctionId());
		Product product = auction.getProduct();
		BigDecimal currentPrice = auction.getCurrentPrice();
		if (auction.isOngoing()) {
			try {
				AuctionState state = auctionRedisService.getState(auctionId);
				if (state.currentPrice() != null) {
					currentPrice = state.currentPrice();
				}
			} catch (RedisConnectionFailureException exception) {
				currentPrice = auction.getCurrentPrice();
			}
		}
		currentPrice = auction.resolveDisplayCurrentPrice(currentPrice);
		Category category = categoryRepository.findById(product.getCategoryId()).orElse(null);
		Member seller = memberRepository.findByMemberIdAndDeletedAtIsNull(product.getMemberId()).orElse(null);
		return new AuctionDetailResponse(
			auction.getAuctionId(),
			product.getProductId(),
			product.getName(),
			product.getDescription(),
			product.getCategoryId(),
			category == null ? "" : category.getNameKo(),
			product.getLocation(),
			toImageResponses(product),
			toSellerResponse(seller),
			auction.getStartPrice(),
			currentPrice,
			auction.getMinimumBidAmount(),
			currentPrice.add(auction.getMinimumBidAmount()),
			(int) bidRepository.countByAuctionAuctionId(auction.getAuctionId()),
			(int) bidRepository.countDistinctBidderIdByAuctionId(auction.getAuctionId()),
			auction.getAuctionStartAt(),
			auction.getEndsAt(),
			serverTime(),
			auction.getStatus(),
			memberId != null && wishlistService.isLiked(memberId, product.getProductId()),
			product.getFavoriteCount()
		);
	}

	public AuctionBidHistoryResponse getBidHistory(Long auctionId) {
		Auction auction = loadAuction(auctionId);
		List<Bid> bids = bidRepository.findAllByAuctionAuctionIdOrderByCreatedAtDescBidIdDesc(auctionId);
		Map<Long, Member> membersById = memberRepository.findAllByMemberIdInAndDeletedAtIsNull(
				bids.stream().map(Bid::getBidderId).collect(Collectors.toSet())
			)
			.stream()
			.collect(Collectors.toMap(Member::getMemberId, Function.identity()));

		List<BidHistoryItem> bidItems = bids.stream()
			.map(bid -> new BidHistoryItem(
				bid.getBidId(),
				bid.getBidderId(),
				resolveBidderNickname(membersById.get(bid.getBidderId()), bid.getBidderId()),
				resolveBidderProfileImageUrl(membersById.get(bid.getBidderId())),
				bid.getBidPrice(),
				bid.getCreatedAt(),
				auction.getWinnerId() == null
					? bid.getBidPrice().compareTo(auction.getCurrentPrice()) == 0
					: bid.getBidderId().equals(auction.getWinnerId())
			))
			.toList();

		return new AuctionBidHistoryResponse(
			auctionId,
			auction.resolveDisplayCurrentPrice(auction.getCurrentPrice()),
			bidItems.size(),
			bidItems
		);
	}

	@Transactional
	public BidResponse bid(Long auctionId, Long bidderId, BigDecimal bidPrice) {
		Auction auction = loadAuctionWithLock(auctionId);
		Long sellerId = auction.getProduct().getMemberId();
		if (sellerId.equals(bidderId)) {
			throw new InvalidAuctionRequestException("자신이 등록한 경매에는 입찰할 수 없습니다.");
		}
		if (!auction.isOngoing()) {
			throw AuctionBidException.auctionEnded();
		}
		if (!auction.getEndsAt().isAfter(serverTime())) {
			throw AuctionBidException.auctionEnded();
		}
		if (bidPrice.compareTo(auction.getCurrentPrice().add(auction.getMinimumBidAmount())) < 0) {
			throw AuctionBidException.priceBelowMinimum();
		}
		Member bidder = loadActiveMember(bidderId);
		if (!bidder.isVerified()) {
			throw new EmailNotVerifiedException();
		}

		long bidAmount = toWalletAmount(bidPrice);
		walletService.reserveAuctionBid(bidderId, bidAmount, auctionId);

		BidScriptResult result = auctionRedisService.bid(auctionId, bidPrice, bidderId);
		switch (result.status()) {
			case AUCTION_ENDED -> throw AuctionBidException.auctionEnded();
			case BID_TOO_LOW -> throw AuctionBidException.priceChanged();
			case SAME_BIDDER -> throw new InvalidAuctionRequestException("현재 최고 입찰자는 다시 입찰할 수 없습니다.");
			case SUCCESS -> {
				try {
					OffsetDateTime serverTime = serverTime();
					auctionPaymentRepository.save(AuctionPayment.reserve(auction, bidderId, sellerId, bidAmount, serverTime));
					requestPreviousHighestBidderRefund(auctionId, result.previousBidderId(), serverTime);
					bidRepository.save(Bid.create(auction, bidderId, bidPrice));
					auction.updateCurrentPrice(bidPrice);
					long bidCount = bidRepository.countByAuctionAuctionId(auction.getAuctionId());
					long bidderCount = bidRepository.countDistinctBidderIdByAuctionId(auction.getAuctionId());
					BigDecimal minimumNextBidPrice = bidPrice.add(auction.getMinimumBidAmount());
					auctionNotificationService.notifyBidReceived(auction, bidderId, bidPrice, bidCount);
					auctionNotificationService.notifyBidPlaced(auction, bidderId, bidPrice);
					auctionNotificationService.notifyOutbid(auction, result.previousBidderId(), bidderId, bidPrice);
					auctionEventPublisher.publishBidUpdated(auctionId, bidPrice, bidderId, serverTime);
					auctionEventPublisher.publishOutbid(auctionId, result.previousBidderId(), bidderId, bidPrice, serverTime);
					auctionEventPublisher.publishBidReceived(
						sellerId,
						auctionId,
						bidderId,
						bidPrice,
						bidCount,
						result.previousBidderId() == null,
						serverTime
					);
					auctionEventPublisher.publishAuctionBidUpdated(
						Arrays.asList(sellerId, bidderId, result.previousBidderId()),
						auctionId,
						bidPrice,
						minimumNextBidPrice,
						bidCount,
						bidderCount,
						serverTime
					);
					long searchVersion = auction.increaseSearchVersion();
					applicationEventPublisher.publishEvent(new AuctionSearchIndexRequestedEvent(auctionId, searchVersion));
					return new BidResponse(auctionId, bidPrice, bidderId, serverTime, BidMessages.defaults());
				} catch (RuntimeException exception) {
					auctionRedisService.restoreBidState(auctionId, bidPrice, bidderId, result.previousPrice(), result.previousBidderId());
					throw exception;
				}
			}
		}
		throw new InvalidAuctionRequestException("입찰을 처리할 수 없습니다.");
	}

	@Transactional
	public void endAuction(Long auctionId) {
		Auction auction = loadAuction(auctionId);
		if (!auction.isOngoing()) {
			return;
		}

		AuctionState state = auctionRedisService.getState(auctionId);
		if (state.highestBidderId() == null) {
			OffsetDateTime noBidAt = serverTime();
			auction.completeWithoutBid(noBidAt, noBidAt.plusDays(3));
			auction.getProduct().markEndedWithoutDelete();
			auctionRedisService.removeEnding(auctionId);
			auctionRedisService.deleteState(auctionId);
			auctionRedisService.removeClosingSoonNotified(auctionId);
			auctionNotificationService.notifyAuctionEnded(
				auction,
				auction.getProduct().getMemberId(),
				null,
				null,
				AuctionStatus.NO_BID
			);
			auctionEventPublisher.publishAuctionEnded(
				auction.getProduct().getMemberId(),
				auctionId,
				null,
				null,
				AuctionStatus.NO_BID,
				serverTime()
			);
			applicationEventPublisher.publishEvent(new AuctionSearchDeleteRequestedEvent(auctionId));
			return;
		}

		auction.completeWithWinner(state.highestBidderId(), state.currentPrice());
		auction.getProduct().markSold();
		Long chatRoomId = ensureAuctionChatRoom(auction, state.highestBidderId());
		AuctionPayment winningPayment = loadWinningPayment(
			auctionId,
			state.highestBidderId(),
			auction.getProduct().getMemberId()
		);
		purchaseService.createAuctionPurchase(auction, winningPayment, chatRoomId);
		auctionRedisService.removeEnding(auctionId);
		auctionRedisService.deleteState(auctionId);
		auctionRedisService.removeClosingSoonNotified(auctionId);
		auctionNotificationService.notifyAuctionEnded(
			auction,
			auction.getProduct().getMemberId(),
			state.highestBidderId(),
			state.currentPrice(),
			AuctionStatus.SUCCESSFUL_BID
		);
		auctionEventPublisher.publishAuctionEnded(
			auction.getProduct().getMemberId(),
			auctionId,
			state.highestBidderId(),
			state.currentPrice(),
			AuctionStatus.SUCCESSFUL_BID,
			serverTime()
		);
		if (!auction.getProduct().getMemberId().equals(state.highestBidderId())) {
			auctionNotificationService.notifyAuctionEnded(
				auction,
				state.highestBidderId(),
				state.highestBidderId(),
				state.currentPrice(),
				AuctionStatus.SUCCESSFUL_BID
			);
			auctionEventPublisher.publishAuctionEnded(
				state.highestBidderId(),
				auctionId,
				state.highestBidderId(),
				state.currentPrice(),
				AuctionStatus.SUCCESSFUL_BID,
				serverTime()
			);
		}
		bidRepository.findDistinctBidderIdsByAuctionIdAndBidderIdNot(auctionId, state.highestBidderId())
			.forEach(bidderId -> auctionNotificationService.notifyAuctionFailed(
				auction,
				bidderId,
				state.highestBidderId(),
				state.currentPrice()
			));
		applicationEventPublisher.publishEvent(new AuctionSearchDeleteRequestedEvent(auctionId));
	}

	private Auction loadAuction(Long auctionId) {
		return auctionRepository.findByAuctionIdAndDeletedAtIsNullAndProductDeletedAtIsNull(auctionId)
			.orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다."));
	}

	private Auction loadAuctionWithLock(Long auctionId) {
		return auctionRepository.findWithLockByAuctionIdAndDeletedAtIsNullAndProductDeletedAtIsNull(auctionId)
			.orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다."));
	}

	private Auction loadAuctionDetail(Long auctionId) {
		return auctionRepository.findDetailByAuctionIdAndDeletedAtIsNullAndProductDeletedAtIsNull(auctionId)
			.orElseThrow(() -> new AuctionNotFoundException("존재하지 않는 경매입니다."));
	}

	private Member loadActiveMember(Long memberId) {
		return memberRepository.findByMemberIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new InvalidAuctionRequestException("존재하지 않는 회원입니다."));
	}

	private void requestPreviousHighestBidderRefund(Long auctionId, Long previousBidderId, OffsetDateTime refundRequestedAt) {
		if (previousBidderId == null) {
			return;
		}
		AuctionPayment previousPayment = auctionPaymentRepository
			.findFirstByAuctionAuctionIdAndBidderIdAndStatusAndDeletedAtIsNullOrderByReservedAtDescAuctionPaymentIdDesc(
				auctionId,
				previousBidderId,
				AuctionPaymentStatus.RESERVED
			)
			.orElseThrow(() -> new InvalidAuctionRequestException("이전 최고 입찰자의 예치금을 찾을 수 없습니다."));
		if (previousPayment.requestRefund(refundRequestedAt)) {
			applicationEventPublisher.publishEvent(new AuctionRefundRequestedEvent(
				auctionId,
				previousPayment.getBidderId(),
				previousPayment.getAuctionPaymentId(),
				previousPayment.getAmount()
			));
		}
	}

	private AuctionPayment loadWinningPayment(Long auctionId, Long winnerId, Long sellerId) {
		return auctionPaymentRepository
			.findFirstByAuctionAuctionIdAndBidderIdAndSellerIdAndStatusInAndDeletedAtIsNullOrderByReservedAtDescAuctionPaymentIdDesc(
				auctionId,
				winnerId,
				sellerId,
				List.of(AuctionPaymentStatus.RESERVED)
			)
			.orElseThrow(() -> new InvalidAuctionRequestException("낙찰자의 예치금을 찾을 수 없습니다."));
	}

	private Long ensureAuctionChatRoom(Auction auction, Long buyerId) {
		Long sellerId = auction.getProduct().getMemberId();
		Long productId = auction.getProduct().getProductId();
		return chatRoomRepository.findBySellerIdAndBuyerIdAndProductIdAndDeletedAtIsNull(sellerId, buyerId, productId)
			.map(chatRoom -> {
				chatRoom.markAuction();
				return chatRoom.getRoomId();
			})
			.orElseGet(() -> chatRoomRepository.save(ChatRoom.create(sellerId, buyerId, productId, ChatType.AUCTION))
				.getRoomId());
	}

	private List<ImageResponse> toImageResponses(Product product) {
		return product.getImages().stream()
			.filter(image -> image.getDeletedAt() == null)
			.filter(image -> image.getImageUrl() != null && !image.getImageUrl().isBlank())
			.sorted(Comparator.comparing(
				ProductImage::getSortOrder,
				Comparator.nullsLast(Integer::compareTo)
			))
			.map(image -> new ImageResponse(
				image.getImageId(),
				imageUrlService.toPublicUrl(image.getImageUrl()),
				image.getSortOrder()
			))
			.toList();
	}

	private SellerResponse toSellerResponse(Member seller) {
		if (seller == null) {
			return null;
		}
		String profileImageUrl = seller.getProfileImage() == null || seller.getProfileImage().isBlank()
			? null
			: imageUrlService.toPublicUrl(seller.getProfileImage());
		return new SellerResponse(
			seller.getMemberId(),
			seller.getNickname(),
			profileImageUrl,
			seller.getIntro(),
			getSellerRating(seller.getMemberId())
		);
	}

	private double getSellerRating(Long sellerId) {
		ReviewRatingSummaryResponse summary = reviewRepository.getRatingSummaryByRevieweeId(sellerId);
		if (summary == null) {
			return 0.0;
		}
		return Math.round(summary.averageRating() * 10.0) / 10.0;
	}

	private String resolveBidderNickname(Member bidder, Long bidderId) {
		if (bidder == null) {
			return "입찰자 #" + bidderId;
		}
		return bidder.getNickname();
	}

	private String resolveBidderProfileImageUrl(Member bidder) {
		if (bidder == null || bidder.getProfileImage() == null || bidder.getProfileImage().isBlank()) {
			return null;
		}
		return imageUrlService.toPublicUrl(bidder.getProfileImage());
	}

	private OffsetDateTime serverTime() {
		return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
	}

	private long toWalletAmount(BigDecimal amount) {
		try {
			return amount.stripTrailingZeros().longValueExact();
		} catch (ArithmeticException exception) {
			throw new InvalidAuctionRequestException("입찰가는 원 단위로 입력해주세요.");
		}
	}
}
