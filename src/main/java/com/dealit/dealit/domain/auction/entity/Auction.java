package com.dealit.dealit.domain.auction.entity;

import com.dealit.dealit.domain.auction.AuctionStatus;
import com.dealit.dealit.domain.product.entity.Product;
import com.dealit.dealit.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

@Getter
@Entity
@Table(name = "auction")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SequenceGenerator(
	name = "auction_seq_generator",
	sequenceName = "auction_seq",
	allocationSize = 1
)
public class Auction extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "auction_seq_generator")
	@Column(name = "auction_id")
	private Long auctionId;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "product_id", nullable = false, unique = true)
	private Product product;

	@Column(name = "start_price", nullable = false, precision = 15, scale = 2)
	private BigDecimal startPrice;

	@Column(name = "current_price", nullable = false, precision = 15, scale = 2)
	private BigDecimal currentPrice;

	@Column(name = "minimum_bid_amount", precision = 15, scale = 2)
	private BigDecimal minimumBidAmount;

	@Column(name = "final_price", precision = 15, scale = 2)
	private BigDecimal finalPrice;

	@Column(name = "winner_id")
	private Long winnerId;

	@Column(name = "started_at", nullable = false)
	private OffsetDateTime startedAt;

	@Column(name = "ends_at", nullable = false)
	private OffsetDateTime endsAt;

	@Column(name = "no_bid_at")
	private OffsetDateTime noBidAt;

	@Column(name = "reauction_expires_at")
	private OffsetDateTime reauctionExpiresAt;

	@Column(name = "reauctioned_at")
	private OffsetDateTime reauctionedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private AuctionStatus status;

	@Column(name = "search_version", nullable = false)
	private long searchVersion = 1L;

	private Auction(
		Product product,
		BigDecimal startPrice,
		BigDecimal currentPrice,
		BigDecimal minimumBidAmount,
		OffsetDateTime startedAt,
		OffsetDateTime endsAt,
		AuctionStatus status
	) {
		this.product = product;
		this.startPrice = startPrice;
		this.currentPrice = currentPrice;
		this.minimumBidAmount = minimumBidAmount;
		this.startedAt = startedAt;
		this.endsAt = endsAt;
		this.status = status;
	}

	public static Auction create(
		Product product,
		BigDecimal startPrice,
		BigDecimal minimumBidAmount,
		OffsetDateTime auctionStartAt,
		OffsetDateTime auctionEndAt,
		AuctionStatus status
	) {
		return new Auction(product, startPrice, startPrice, minimumBidAmount, auctionStartAt, auctionEndAt, status);
	}

	public void updateEditableDetails(BigDecimal startPrice, BigDecimal minimumBidAmount, OffsetDateTime auctionEndAt) {
		this.startPrice = startPrice;
		this.currentPrice = startPrice;
		this.minimumBidAmount = minimumBidAmount;
		this.endsAt = auctionEndAt;
	}

	public void updateCurrentPrice(BigDecimal currentPrice) {
		this.currentPrice = currentPrice;
	}

	public long increaseSearchVersion() {
		return ++this.searchVersion;
	}

	public BigDecimal getMinimumBidAmount() {
		if (minimumBidAmount != null) {
			return minimumBidAmount;
		}
		return startPrice.multiply(BigDecimal.valueOf(0.01)).setScale(0, RoundingMode.CEILING);
	}

	public BigDecimal resolveDisplayCurrentPrice(BigDecimal currentPrice) {
		if (currentPrice == null || currentPrice.signum() <= 0) {
			return startPrice;
		}
		return currentPrice;
	}

	public void completeWithWinner(Long winnerId, BigDecimal finalPrice) {
		this.winnerId = winnerId;
		this.finalPrice = finalPrice;
		this.currentPrice = finalPrice;
		this.status = AuctionStatus.SUCCESSFUL_BID;
	}

	public void completeWithoutBid(OffsetDateTime noBidAt, OffsetDateTime reauctionExpiresAt) {
		this.finalPrice = null;
		this.winnerId = null;
		this.noBidAt = noBidAt;
		this.reauctionExpiresAt = reauctionExpiresAt;
		this.status = AuctionStatus.NO_BID;
	}

	public void declineReauction() {
		this.status = AuctionStatus.ENDED;
	}

	public void markReauctioned(OffsetDateTime reauctionedAt) {
		this.reauctionedAt = reauctionedAt;
		this.status = AuctionStatus.ENDED;
	}

	public void expireReauction() {
		this.status = AuctionStatus.ENDED;
	}

	public boolean isOngoing() {
		return this.status == AuctionStatus.ONGOING;
	}

	public OffsetDateTime getAuctionStartAt() {
		return startedAt;
	}

	public OffsetDateTime getAuctionEndAt() {
		return endsAt;
	}
}
